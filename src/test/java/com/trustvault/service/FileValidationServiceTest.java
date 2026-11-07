package com.trustvault.service;

import com.trustvault.util.AuditHasher;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

class FileValidationServiceTest {

    private final FileValidationService service = new FileValidationService(new DefaultMalwareScanner());

    @Test
    void acceptsPlainTextAndReturnsContentHash() throws Exception {

        MockMultipartFile file = new MockMultipartFile(
                "file", "notes.txt", "text/plain",
                "hello trustvault".getBytes(StandardCharsets.UTF_8));

        FileValidationService.ValidatedFile validated = service.validate(file);

        assertEquals("notes.txt", validated.fileName());
        assertEquals("text/plain", validated.contentType());
        assertEquals(16, validated.size());
        assertEquals(
                AuditHasher.sha256("hello trustvault"),
                validated.sha256());
    }

    @Test
    void rejectsEmptyFile() {

        MockMultipartFile file = new MockMultipartFile(
                "file", "empty.txt", "text/plain", new byte[0]);

        assertThrows(IllegalArgumentException.class, () -> service.validate(file));
    }

    @Test
    void rejectsUnsupportedExtension() {

        MockMultipartFile file = new MockMultipartFile(
                "file", "payload.exe", "application/octet-stream",
                new byte[]{0x4D, 0x5A});

        assertThrows(IllegalArgumentException.class, () -> service.validate(file));
    }

    @Test
    void rejectsContentThatDoesNotMatchItsExtension() {

        // A text file renamed to .pdf must not pass the signature check.
        MockMultipartFile file = new MockMultipartFile(
                "file", "invoice.pdf", "application/pdf",
                "not really a pdf".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> service.validate(file));
    }

    @Test
    void rejectsPathTraversalFileName() {

        MockMultipartFile file = new MockMultipartFile(
                "file", "../../etc/passwd.txt", "text/plain",
                "x".getBytes(StandardCharsets.UTF_8));

        assertThrows(IllegalArgumentException.class, () -> service.validate(file));
    }

    @Test
    void rejectsOversizedFile() {

        byte[] big = new byte[(int) FileValidationService.MAX_FILE_SIZE + 1];

        MockMultipartFile file = new MockMultipartFile(
                "file", "big.txt", "text/plain", big);

        assertThrows(IllegalArgumentException.class, () -> service.validate(file));
    }

    @Test
    void stripsALeadingDotSoFilesCannotBeHidden() {

        assertEquals("gitignore.txt",
                service.sanitizeFileName(".gitignore.txt"));
    }

    @Test
    void rejectsNamesContainingDotDot() {

        assertThrows(IllegalArgumentException.class,
                () -> service.sanitizeFileName("..hidden.txt"));
    }

    @Test
    void derivesContentTypeFromTheExtensionNotTheClient() {

        MockMultipartFile file = new MockMultipartFile(
                "file", "photo.png", "text/html",
                new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47,
                        0x0D, 0x0A, 0x1A, 0x0A});

        assertDoesNotThrow(() -> {
            FileValidationService.ValidatedFile validated = service.validate(file);
            assertEquals("image/png", validated.contentType());
        });
    }
}
