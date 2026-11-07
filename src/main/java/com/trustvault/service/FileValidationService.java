package com.trustvault.service;

import com.trustvault.util.AuditHasher;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

/**
 * Validates an upload before anything touches disk or object storage.
 *
 * Implements:
 *  1. Sanitization of declared filenames and path-traversal prevention,
 *  2. Bounded size limit (20MB) and strict extension whitelisting,
 *  3. Magic-byte signature verification,
 *  4. Decompression bomb protection for archive-based formats (.docx),
 *  5. Malware scanning integration point.
 */
@Service
public class FileValidationService {

    public static final long MAX_FILE_SIZE = 20L * 1024L * 1024L;
    private static final int MAX_FILE_NAME = 255;
    private static final int MAX_ZIP_ENTRIES = 1000;
    private static final long MAX_UNCOMPRESSED_SIZE = 50L * 1024L * 1024L; // 50MB
    private static final double MAX_COMPRESSION_RATIO = 100.0;

    private static final Set<String> ALLOWED_EXTENSIONS =
            Set.of("pdf", "png", "jpg", "jpeg", "docx", "txt");

    private static final Map<String, String> CONTENT_TYPES = Map.of(
            "pdf", "application/pdf",
            "png", "image/png",
            "jpg", "image/jpeg",
            "jpeg", "image/jpeg",
            "docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "txt", "text/plain"
    );

    private final MalwareScanner malwareScanner;

    public FileValidationService() {
        this(new DefaultMalwareScanner());
    }

    public FileValidationService(MalwareScanner malwareScanner) {
        this.malwareScanner = malwareScanner != null ? malwareScanner : new DefaultMalwareScanner();
    }

    public ValidatedFile validate(MultipartFile file) throws IOException {

        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("File cannot be empty");
        }

        if (file.getSize() > MAX_FILE_SIZE) {
            throw new IllegalArgumentException("File size cannot exceed 20 MB");
        }

        String safeName = sanitizeFileName(file.getOriginalFilename());
        String extension = extensionOf(safeName);

        if (!ALLOWED_EXTENSIONS.contains(extension)) {
            throw new IllegalArgumentException("This file type is not supported");
        }

        byte[] content = file.getBytes();

        if (!hasValidSignature(extension, content)) {
            throw new IllegalArgumentException(
                    "File content does not match its extension");
        }

        // Decompression bomb defense for archive formats
        if ("docx".equals(extension)) {
            validateDocxArchive(content);
        }

        // External malware scanning verification hook
        malwareScanner.scan(file.getInputStream(), safeName);

        return new ValidatedFile(
                safeName,
                CONTENT_TYPES.get(extension),
                file.getSize(),
                AuditHasher.sha256(content)
        );
    }

    public String sanitizeFileName(String original) {

        if (original == null || original.isBlank()) {
            throw new IllegalArgumentException("Invalid file name");
        }

        String name = original.trim();

        if (name.contains("/") || name.contains("\\")
                || name.contains("..") || name.indexOf('\0') >= 0) {
            throw new IllegalArgumentException("Invalid file name");
        }

        // Strip control characters and path-ish characters that some clients
        // still send, then trim leading dots so the file cannot be hidden.
        name = name.replaceAll("[\\p{Cntrl}]", "").trim();

        while (name.startsWith(".")) {
            name = name.substring(1);
        }

        if (name.isBlank() || name.length() > MAX_FILE_NAME) {
            throw new IllegalArgumentException("Invalid file name");
        }

        if (extensionOf(name).isEmpty()) {
            throw new IllegalArgumentException("File must have a valid extension");
        }

        return name;
    }

    private String extensionOf(String fileName) {
        int dotIndex = fileName.lastIndexOf('.');
        if (dotIndex < 1 || dotIndex == fileName.length() - 1) {
            return "";
        }
        return fileName.substring(dotIndex + 1).toLowerCase(Locale.ROOT);
    }

    private boolean hasValidSignature(String extension, byte[] content) {

        if (content.length == 0) {
            return false;
        }

        return switch (extension) {
            case "pdf" -> startsWith(content, new byte[]{37, 80, 68, 70, 45});
            case "png" -> startsWith(content, new byte[]{
                    (byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A});
            case "jpg", "jpeg" -> content.length >= 3
                    && (content[0] & 0xFF) == 0xFF
                    && (content[1] & 0xFF) == 0xD8
                    && (content[2] & 0xFF) == 0xFF;
            case "docx" -> startsWith(content, new byte[]{0x50, 0x4B, 0x03, 0x04});
            case "txt" -> !containsNullByte(content);
            default -> false;
        };
    }

    private void validateDocxArchive(byte[] content) {
        try (ZipInputStream zis = new ZipInputStream(new ByteArrayInputStream(content))) {
            ZipEntry entry;
            int totalEntries = 0;
            long totalUncompressedBytes = 0;
            byte[] buffer = new byte[4096];

            while ((entry = zis.getNextEntry()) != null) {
                totalEntries++;
                if (totalEntries > MAX_ZIP_ENTRIES) {
                    throw new IllegalArgumentException("DOCX file contains too many entries (possible compression bomb)");
                }

                String entryName = entry.getName();
                if (entryName.contains("..") || entryName.startsWith("/") || entryName.startsWith("\\")) {
                    throw new IllegalArgumentException("DOCX contains invalid path traversal in entry");
                }

                int read;
                while ((read = zis.read(buffer)) != -1) {
                    totalUncompressedBytes += read;
                    if (totalUncompressedBytes > MAX_UNCOMPRESSED_SIZE) {
                        throw new IllegalArgumentException("DOCX uncompressed size exceeds allowable threshold (possible compression bomb)");
                    }
                }
                zis.closeEntry();
            }

            if (content.length > 0 && totalUncompressedBytes > 0) {
                double ratio = (double) totalUncompressedBytes / (double) content.length;
                if (ratio > MAX_COMPRESSION_RATIO) {
                    throw new IllegalArgumentException("DOCX compression ratio is excessively high (possible compression bomb)");
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("Corrupted DOCX archive structure", e);
        }
    }

    private boolean startsWith(byte[] content, byte[] signature) {
        if (content.length < signature.length) {
            return false;
        }
        for (int index = 0; index < signature.length; index++) {
            if (content[index] != signature[index]) {
                return false;
            }
        }
        return true;
    }

    private boolean containsNullByte(byte[] content) {
        for (byte value : content) {
            if (value == 0) {
                return true;
            }
        }
        return false;
    }

    public record ValidatedFile(
            String fileName,
            String contentType,
            long size,
            String sha256
    ) {
    }
}
