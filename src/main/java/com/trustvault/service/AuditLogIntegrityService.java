package com.trustvault.service;

import com.trustvault.dto.AuditVerificationResponse;
import com.trustvault.entity.AuditLog;
import com.trustvault.repository.AuditLogRepository;
import com.trustvault.util.AuditHasher;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;

import java.util.List;

/**
 * Recomputes the audit hash chain from the stored rows.
 *
 * The point of the check is to detect a row whose contents were changed, a row
 * whose link to its predecessor was broken, and rows in the wrong order. It
 * cannot detect a rewrite of the entire chain from the beginning, or removal
 * of trailing rows - see THREATS.md, where that limitation is stated plainly
 * rather than hidden.
 */
@Service
public class AuditLogIntegrityService {

    private final AuditLogRepository auditLogRepository;

    public AuditLogIntegrityService(AuditLogRepository auditLogRepository) {
        this.auditLogRepository = auditLogRepository;
    }

    public AuditVerificationResponse verify() {

        // Ordering is part of the check: the chain is meaningful only in
        // insertion order, so it is asked for explicitly by id.
        List<AuditLog> logs = auditLogRepository.findAll(
                Sort.by(Sort.Direction.ASC, "id"));

        String expectedPreviousHash = AuditHasher.GENESIS;
        long previousId = -1;
        int idGaps = 0;
        int checked = 0;

        for (AuditLog log : logs) {

            if (log.getPreviousHash() == null || log.getEntryHash() == null) {
                return AuditVerificationResponse.broken(
                        checked, log.getId(),
                        "Record " + log.getId() + " is missing its hash fields");
            }

            if (!expectedPreviousHash.equals(log.getPreviousHash())) {
                return AuditVerificationResponse.broken(
                        checked, log.getId(),
                        "Record " + log.getId()
                                + " does not link to the hash of the record before it");
            }

            String recomputed = AuditHasher.entryHash(log);

            if (!recomputed.equals(log.getEntryHash())) {
                return AuditVerificationResponse.broken(
                        checked, log.getId(),
                        "Record " + log.getId()
                                + " contents no longer match its stored hash");
            }

            // A gap is reported but is not by itself proof of tampering:
            // MySQL consumes an auto-increment value for a rolled back insert.
            if (previousId != -1 && log.getId() != previousId + 1) {
                idGaps++;
            }

            previousId = log.getId();
            expectedPreviousHash = log.getEntryHash();
            checked++;
        }

        return AuditVerificationResponse.valid(checked, idGaps, expectedPreviousHash);
    }
}
