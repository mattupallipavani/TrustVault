package com.trustvault.service;

import com.trustvault.entity.Document;
import com.trustvault.entity.User;
import com.trustvault.enums.Role;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * The integrity check on download is the last line between a tampered object
 * and the user, and a presigned URL is a credential handed out on trust. Both
 * are tested here rather than only through the happy-path integration test.
 */
@ExtendWith(MockitoExtension.class)
class DocumentDownloadServiceTest {

    private static final String STORAGE_KEY = "11111111-1111-1111-1111-111111111111";
    private static final String HASH = "abc123";

    @Mock
    private StorageService storageService;

    @Mock
    private PermissionService permissionService;

    @Mock
    private AuditLogService auditLogService;

    private DocumentDownloadService service;
    private Document document;
    private User actor;

    @BeforeEach
    void setUp() {
        service = new DocumentDownloadService(storageService, permissionService, auditLogService);

        document = new Document();
        document.setId(7L);
        document.setFileName("contract.pdf");
        document.setContentHash(HASH);
        document.setFileSize(100L);
        document.setStorageKey(STORAGE_KEY);

        actor = new User();
        actor.setId(1L);
        actor.setEmail("owner@trustvault.local");
        actor.setRole(Role.OWNER);

        // Not every case reaches the permission check (an unsupported backend
        // is rejected before it), so this stub is intentionally lenient.
        lenient().when(permissionService.requireRead(7L, actor)).thenReturn(document);
    }

    @Test
    void refusesDownloadWhenMetadataHashDiffers() throws IOException {
        when(storageService.exists(STORAGE_KEY)).thenReturn(true);
        when(storageService.sizeOf(STORAGE_KEY)).thenReturn(100L);
        when(storageService.storedHash(STORAGE_KEY)).thenReturn("tampered");

        assertThrows(IllegalStateException.class, () -> service.downloadDocument(7L, actor));

        verify(auditLogService).failure(any());
        verify(storageService, never()).retrieve(STORAGE_KEY);
    }

    @Test
    void refusesDownloadWhenSizeDiffers() throws IOException {
        when(storageService.exists(STORAGE_KEY)).thenReturn(true);
        when(storageService.sizeOf(STORAGE_KEY)).thenReturn(99L);

        assertThrows(IllegalStateException.class, () -> service.downloadDocument(7L, actor));

        verify(storageService, never()).retrieve(STORAGE_KEY);
    }

    @Test
    void fallsBackToFullHashWhenProviderKeepsNoMetadata() throws IOException {
        when(storageService.exists(STORAGE_KEY)).thenReturn(true);
        when(storageService.sizeOf(STORAGE_KEY)).thenReturn(100L);
        when(storageService.storedHash(STORAGE_KEY)).thenReturn(null);
        when(storageService.hashOf(STORAGE_KEY)).thenReturn(HASH);
        when(storageService.retrieve(STORAGE_KEY))
                .thenReturn(new java.io.ByteArrayInputStream(new byte[]{1, 2, 3}));

        DocumentDownloadService.DownloadPayload payload = service.downloadDocument(7L, actor);

        assertEquals("contract.pdf", payload.fileName());
        verify(storageService).hashOf(STORAGE_KEY);
    }

    @Test
    void presignIsRefusedWhenBackendCannotIssueLinks() {
        when(storageService.supportsPresignedDownloads()).thenReturn(false);

        assertThrows(UnsupportedOperationException.class,
                () -> service.presignDownload(7L, actor, Duration.ofSeconds(60)));

        verify(permissionService, never()).requireRead(any(), any());
    }

    @Test
    void presignChecksPermissionThenIntegrityThenAudits() throws IOException {
        when(storageService.supportsPresignedDownloads()).thenReturn(true);
        when(storageService.exists(STORAGE_KEY)).thenReturn(true);
        when(storageService.sizeOf(STORAGE_KEY)).thenReturn(100L);
        when(storageService.storedHash(STORAGE_KEY)).thenReturn(HASH);
        when(storageService.presignedDownloadUrl(STORAGE_KEY, Duration.ofSeconds(60)))
                .thenReturn("http://127.0.0.1:9000/bucket/key?signature=...");

        DocumentDownloadService.PresignedDownload link =
                service.presignDownload(7L, actor, Duration.ofSeconds(60));

        assertEquals(60, link.expiresInSeconds());
        assertTrue(link.url().contains("signature"));
        verify(auditLogService).success(any());
    }

    @Test
    void presignDoesNotHandOutLinkForTamperedObject() throws IOException {
        when(storageService.supportsPresignedDownloads()).thenReturn(true);
        when(storageService.exists(STORAGE_KEY)).thenReturn(true);
        when(storageService.sizeOf(STORAGE_KEY)).thenReturn(100L);
        when(storageService.storedHash(STORAGE_KEY)).thenReturn("tampered");

        assertThrows(IllegalStateException.class,
                () -> service.presignDownload(7L, actor, Duration.ofSeconds(60)));

        verify(storageService, never()).presignedDownloadUrl(any(), any());
    }
}
