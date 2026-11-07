package com.trustvault.service;

import com.trustvault.dto.AuditVerificationResponse;
import com.trustvault.entity.AuditLog;
import com.trustvault.repository.AuditLogRepository;
import com.trustvault.util.AuditHasher;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Sort;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuditLogIntegrityServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @InjectMocks
    private AuditLogIntegrityService integrityService;

    @Test
    void acceptsAValidChain() {

        when(auditLogRepository.findAll(any(Sort.class)))
                .thenReturn(chain("UPLOAD", "DOWNLOAD"));

        assertTrue(integrityService.verify().isValid());
    }

    @Test
    void detectsAChangedField() {

        List<AuditLog> logs = chain("UPLOAD", "DOWNLOAD");
        logs.get(1).setDetails("tampered with");

        when(auditLogRepository.findAll(any(Sort.class))).thenReturn(logs);

        AuditVerificationResponse result = integrityService.verify();

        assertFalse(result.isValid());
        assertEquals(2L, result.getBrokenAtId());
    }

    @Test
    void detectsABrokenLink() {

        List<AuditLog> logs = chain("UPLOAD", "DOWNLOAD");
        logs.get(1).setPreviousHash("not-the-previous-hash");

        when(auditLogRepository.findAll(any(Sort.class))).thenReturn(logs);

        assertFalse(integrityService.verify().isValid());
    }

    @Test
    void detectsAReorderedChain() {

        List<AuditLog> logs = chain("UPLOAD", "DOWNLOAD");
        AuditLog first = logs.get(0);
        AuditLog second = logs.get(1);

        // Swap ids so the stored order no longer matches the links.
        first.setId(2L);
        second.setId(1L);

        when(auditLogRepository.findAll(any(Sort.class)))
                .thenReturn(List.of(second, first));

        assertFalse(integrityService.verify().isValid());
    }

    @Test
    void emptyChainIsTriviallyValid() {

        when(auditLogRepository.findAll(any(Sort.class))).thenReturn(List.of());

        assertTrue(integrityService.verify().isValid());
    }

    /**
     * Builds a correctly hashed chain so tests can break exactly one thing.
     */
    private List<AuditLog> chain(String... actions) {

        List<AuditLog> logs = new java.util.ArrayList<>();
        String previous = AuditHasher.GENESIS;

        long id = 1;

        for (String action : actions) {

            AuditLog log = new AuditLog();
            log.setId(id++);
            log.setActorUserId(1L);
            log.setActorEmail("owner@trustvault.local");
            log.setAction(action);
            log.setOutcome("SUCCESS");
            log.setDetails(action + " happened");
            log.setCreatedAt(LocalDateTime.of(2026, 11, 4, 10, 0, (int) id));
            log.setPreviousHash(previous);

            log.setEntryHash(AuditHasher.entryHash(log));
            previous = log.getEntryHash();

            logs.add(log);
        }

        return logs;
    }
}
