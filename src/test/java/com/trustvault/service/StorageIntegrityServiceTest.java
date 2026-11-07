package com.trustvault.service;

import com.trustvault.entity.Document;
import com.trustvault.repository.DocumentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The integrity sweep is the only thing that checks the whole archive rather
 * than the one document a user happened to request, so its verdicts matter:
 * a false "healthy" is worse than no check at all.
 */
@ExtendWith(MockitoExtension.class)
class StorageIntegrityServiceTest {

    @Mock
    private DocumentRepository documentRepository;

    @Mock
    private StorageService storageService;

    @Mock
    private AuditLogService auditLogService;

    private StorageIntegrityService service;

    @BeforeEach
    void setUp() {
        service = new StorageIntegrityService(documentRepository, storageService, auditLogService);
    }

    private Document document(long id, String hash, long size) {
        Document document = new Document();
        document.setId(id);
        document.setFileName("file-" + id + ".pdf");
        document.setStorageKey("11111111-1111-1111-1111-11111111111" + id);
        document.setContentHash(hash);
        document.setFileSize(size);
        return document;
    }

    @Test
    void reportsHealthyArchiveWhenEveryObjectMatches() throws IOException {
        Document document = document(1, "abc123", 10L);
        when(documentRepository.findAll()).thenReturn(List.of(document));
        when(storageService.exists(document.getStorageKey())).thenReturn(true);
        when(storageService.sizeOf(document.getStorageKey())).thenReturn(10L);
        when(storageService.storedHash(document.getStorageKey())).thenReturn("abc123");

        StorageIntegrityService.Report report = service.verify(false);

        assertTrue(report.allHealthy());
        assertEquals(1, report.checked());
        assertEquals(0, report.missing());
        assertTrue(report.issues().isEmpty());
        verify(auditLogService).success(any());
    }

    @Test
    void countsMissingObjectAndFailsTheSweep() {
        Document document = document(2, "abc123", 10L);
        when(documentRepository.findAll()).thenReturn(List.of(document));
        when(storageService.exists(document.getStorageKey())).thenReturn(false);

        StorageIntegrityService.Report report = service.verify(false);

        assertFalse(report.allHealthy());
        assertEquals(1, report.missing());
        assertEquals("file-2.pdf", report.issues().get(0).fileName());
        verify(auditLogService).failure(any());
        verify(auditLogService, never()).success(any());
    }

    @Test
    void detectsTruncatedObjectBySizeWithoutReadingIt() throws IOException {
        Document document = document(3, "abc123", 10L);
        when(documentRepository.findAll()).thenReturn(List.of(document));
        when(storageService.exists(document.getStorageKey())).thenReturn(true);
        when(storageService.sizeOf(document.getStorageKey())).thenReturn(4L);

        StorageIntegrityService.Report report = service.verify(false);

        assertFalse(report.allHealthy());
        assertEquals(1, report.sizeMismatched());
        verify(storageService, never()).hashOf(anyString());
    }

    @Test
    void detectsSwappedBytesFromRecordedHash() throws IOException {
        Document document = document(4, "abc123", 10L);
        when(documentRepository.findAll()).thenReturn(List.of(document));
        when(storageService.exists(document.getStorageKey())).thenReturn(true);
        when(storageService.sizeOf(document.getStorageKey())).thenReturn(10L);
        when(storageService.storedHash(document.getStorageKey())).thenReturn("deadbeef");

        StorageIntegrityService.Report report = service.verify(false);

        assertFalse(report.allHealthy());
        assertEquals(1, report.hashMismatched());
    }

    @Test
    void deepSweepRecomputesInsteadOfTrustingMetadata() throws IOException {
        Document document = document(5, "abc123", 10L);
        when(documentRepository.findAll()).thenReturn(List.of(document));
        when(storageService.exists(document.getStorageKey())).thenReturn(true);
        when(storageService.sizeOf(document.getStorageKey())).thenReturn(10L);
        when(storageService.hashOf(document.getStorageKey())).thenReturn("abc123");

        StorageIntegrityService.Report report = service.verify(true);

        assertTrue(report.allHealthy());
        assertTrue(report.deep());
        verify(storageService).hashOf(document.getStorageKey());
        verify(storageService, never()).storedHash(anyString());
    }
}
