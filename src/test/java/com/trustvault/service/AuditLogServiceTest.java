package com.trustvault.service;

import com.trustvault.entity.AuditLog;
import com.trustvault.repository.AuditLogRepository;
import com.trustvault.util.AuditEvent;
import com.trustvault.util.AuditHasher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditLogService auditLogService;

    @Test
    void firstEntryIsChainedToGenesis() {

        when(auditLogRepository.findTopByOrderByIdDesc())
                .thenReturn(Optional.empty());

        auditLogService.success(
                AuditEvent.by("UPLOAD", null).details("first"));

        AuditLog written = capturedEntry();

        assertEquals(AuditHasher.GENESIS, written.getPreviousHash());
        assertEquals("SUCCESS", written.getOutcome());
        assertEquals(AuditHasher.entryHash(written), written.getEntryHash());
    }

    @Test
    void subsequentEntryChainsOntoThePreviousHash() {

        AuditLog head = new AuditLog();
        head.setId(5L);
        head.setEntryHash("abc123");

        when(auditLogRepository.findTopByOrderByIdDesc())
                .thenReturn(Optional.of(head));

        auditLogService.success(
                AuditEvent.by("DOWNLOAD", null).details("second"));

        assertEquals("abc123", capturedEntry().getPreviousHash());
    }

    @Test
    void deniedAttemptIsRecordedAsDenied() {

        when(auditLogRepository.findTopByOrderByIdDesc())
                .thenReturn(Optional.empty());

        auditLogService.denied(
                AuditEvent.by("VIEW", null).details("no grant"));

        assertEquals("DENIED", capturedEntry().getOutcome());
    }

    @Test
    void hashCoversTheEventContents() {

        when(auditLogRepository.findTopByOrderByIdDesc())
                .thenReturn(Optional.empty());

        auditLogService.success(
                AuditEvent.by("DELETE", null).details("removed"));

        AuditLog written = capturedEntry();
        String originalHash = written.getEntryHash();

        written.setDetails("something else");

        assertNotEquals(originalHash, AuditHasher.entryHash(written));
    }

    /**
     * created_at must be stored at a precision the database can return
     * unchanged, otherwise verification fails on every row after a restart.
     */
    @Test
    void createdAtIsTruncatedToMicroseconds() throws Exception {

        when(auditLogRepository.findTopByOrderByIdDesc())
                .thenReturn(Optional.empty());

        auditLogService.success(AuditEvent.by("UPLOAD", null));

        AuditLog written = capturedEntry();

        Field field = AuditLog.class.getDeclaredField("createdAt");
        field.setAccessible(true);
        java.time.LocalDateTime value =
                (java.time.LocalDateTime) field.get(written);

        assertEquals(0, value.getNano() % 1000,
                "nanoseconds beyond microseconds would not survive storage");
    }

    private AuditLog capturedEntry() {

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditLogRepository).saveAndFlush(captor.capture());
        return captor.getValue();
    }
}
