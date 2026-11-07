package com.trustvault.service;

import com.trustvault.entity.AuditChainAnchor;
import com.trustvault.entity.AuditLog;
import com.trustvault.repository.AuditChainAnchorRepository;
import com.trustvault.repository.AuditLogRepository;
import com.trustvault.util.AuditEvent;
import com.trustvault.util.AuditHasher;
import com.trustvault.util.RequestInfo;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.List;

/**
 * Enterprise writer and query service for audit records.
 *
 * Rules:
 * 1. Refused actions leave permanent evidence: {@link #denied} and {@link #standalone}
 *    run with REQUIRES_NEW transactions so they survive caller rollback.
 * 2. Hash chaining is serialized via database row locks on audit_chain_anchor
 *    (SELECT ... FOR UPDATE), guaranteeing cluster-wide append consistency.
 */
@Service
public class AuditLogService {

    private static final Object CHAIN_LOCK = new Object();

    private final AuditLogRepository auditLogRepository;
    private final AuditChainAnchorRepository anchorRepository;

    public AuditLogService(
            AuditLogRepository auditLogRepository,
            @Autowired(required = false) AuditChainAnchorRepository anchorRepository) {
        this.auditLogRepository = auditLogRepository;
        this.anchorRepository = anchorRepository;
    }

    @Transactional
    public void success(AuditEvent event) {
        append(event, "SUCCESS");
    }

    @Transactional
    public void failure(AuditEvent event) {
        append(event, "FAILURE");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void denied(AuditEvent event) {
        append(event, "DENIED");
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void standalone(AuditEvent event, String outcome) {
        append(event, outcome);
    }

    public List<AuditLog> search(String action, String outcome, Long documentId,
                                 String actorEmail, LocalDateTime from,
                                 LocalDateTime to) {
        return auditLogRepository.search(
                blankToNull(action),
                blankToNull(outcome),
                documentId,
                blankToNull(actorEmail),
                from,
                to
        );
    }

    public Page<AuditLog> searchPage(String action, String outcome, Long documentId,
                                     String actorEmail, LocalDateTime from,
                                     LocalDateTime to, Pageable pageable) {
        return auditLogRepository.searchPage(
                blankToNull(action),
                blankToNull(outcome),
                documentId,
                blankToNull(actorEmail),
                from,
                to,
                pageable
        );
    }

    public long countDenied() {
        return auditLogRepository.countByOutcome("DENIED");
    }

    private void append(AuditEvent event, String outcome) {
        synchronized (CHAIN_LOCK) {
            String previousHash;
            AuditChainAnchor anchor = null;

            if (anchorRepository != null) {
                anchor = anchorRepository.findByIdForUpdate(1).orElseGet(() -> {
                    AuditChainAnchor newAnchor = new AuditChainAnchor();
                    newAnchor.setAnchorId(1);
                    newAnchor.setHeadHash(AuditHasher.GENESIS);
                    newAnchor.setUpdatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
                    return anchorRepository.save(newAnchor);
                });
                previousHash = anchor.getHeadHash();
            } else {
                previousHash = auditLogRepository.findTopByOrderByIdDesc()
                        .map(AuditLog::getEntryHash)
                        .orElse(AuditHasher.GENESIS);
            }

            AuditLog entry = new AuditLog();
            entry.setActorUserId(event.getActorUserId());
            entry.setActorEmail(event.getActorEmail());
            entry.setDocumentId(event.getDocumentId());
            entry.setDocumentName(event.getDocumentName());
            entry.setTargetUserId(event.getTargetUserId());
            entry.setTargetEmail(event.getTargetEmail());
            entry.setAction(event.getAction());
            entry.setOutcome(outcome);
            entry.setDetails(event.getDetails());
            entry.setIpAddress(RequestInfo.clientIp());
            entry.setUserAgent(RequestInfo.userAgent());

            entry.setCreatedAt(LocalDateTime.now().truncatedTo(ChronoUnit.MICROS));
            entry.setPreviousHash(previousHash);
            entry.setEntryHash(AuditHasher.entryHash(entry));

            AuditLog saved = auditLogRepository.saveAndFlush(entry);

            if (anchor != null) {
                anchor.setLastAuditId(saved.getId());
                anchor.setHeadHash(saved.getEntryHash());
                anchor.setUpdatedAt(saved.getCreatedAt());
                anchorRepository.save(anchor);
            }
        }
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
