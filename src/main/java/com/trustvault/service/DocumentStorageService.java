package com.trustvault.service;

import com.trustvault.util.AuditHasher;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Files on disk, addressed only by an opaque UUID key.
 * Used as default local storage or fallback when trustvault.storage.type=local.
 */
@Service
@ConditionalOnProperty(name = "trustvault.storage.type", havingValue = "local", matchIfMissing = true)
public class DocumentStorageService implements StorageService {

    private static final Pattern KEY_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}"
                    + "-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final Path storageLocation;

    public DocumentStorageService(
            @Value("${trustvault.storage.location:./trustvault-storage}") String location)
            throws IOException {

        this.storageLocation = Paths.get(location).toAbsolutePath().normalize();
        Files.createDirectories(this.storageLocation);
    }

    public Path getStorageLocation() {
        return storageLocation;
    }

    public String store(MultipartFile file) throws IOException {
        if (file == null || file.isEmpty()) {
            throw new IllegalArgumentException("Cannot upload an empty file");
        }

        String storageKey = UUID.randomUUID().toString();
        try (InputStream input = file.getInputStream()) {
            store(storageKey, input, file.getSize(), file.getContentType());
        }
        return storageKey;
    }

    @Override
    public void store(String storageKey, InputStream inputStream, long size, String contentType) throws IOException {
        Path target = resolve(storageKey);
        Files.copy(inputStream, target, StandardCopyOption.REPLACE_EXISTING);
    }

    @Override
    public InputStream retrieve(String storageKey) throws IOException {
        Path path = resolve(storageKey);
        return Files.newInputStream(path);
    }

    public Path resolve(String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Invalid storage key");
        }

        Path resolved = storageLocation.resolve(storageKey).normalize();

        if (!resolved.startsWith(storageLocation)) {
            throw new IllegalArgumentException("Invalid storage key");
        }

        return resolved;
    }

    @Override
    public boolean exists(String storageKey) {
        try {
            return Files.exists(resolve(storageKey));
        } catch (IllegalArgumentException e) {
            return false;
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        Files.deleteIfExists(resolve(storageKey));
    }

    @Override
    public long sizeOf(String storageKey) throws IOException {
        return Files.size(resolve(storageKey));
    }

    @Override
    public String hashOf(String storageKey) throws IOException {
        Path path = resolve(storageKey);
        try (InputStream stream = Files.newInputStream(path)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }
}
