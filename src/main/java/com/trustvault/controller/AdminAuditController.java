package com.trustvault.controller;

import com.trustvault.dto.AuditLogResponse;
import com.trustvault.dto.AuditVerificationResponse;
import com.trustvault.entity.AuditLog;
import com.trustvault.service.AuditLogIntegrityService;
import com.trustvault.service.AuditLogService;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

/**
 * Administrative visibility. ADMIN only, enforced twice: by the URL rule in
 * SecurityConfig and by the method annotation below.
 *
 * Note what an administrator cannot do here: read a document. There is no
 * route from this controller to document content, so an admin account
 * compromise does not turn into a confidentiality breach of the files
 * themselves. That separation is a deliberate design decision.
 */
@RestController
@RequestMapping("/api/admin")
@PreAuthorize("hasRole('ADMIN')")
public class AdminAuditController {

    private final AuditLogService auditLogService;
    private final AuditLogIntegrityService integrityService;

    public AdminAuditController(AuditLogService auditLogService,
                                AuditLogIntegrityService integrityService) {
        this.auditLogService = auditLogService;
        this.integrityService = integrityService;
    }

    @GetMapping("/audit")
    public List<AuditLogResponse> getAuditLogs(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime to) {

        return auditLogService
                .search(action, outcome, documentId, actorEmail, from, to)
                .stream()
                .map(this::convertToResponse)
                .toList();
    }

    @GetMapping("/audit/paged")
    public org.springframework.data.domain.Page<AuditLogResponse> getAuditLogsPaged(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) String outcome,
            @RequestParam(required = false) Long documentId,
            @RequestParam(required = false) String actorEmail,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {

        org.springframework.data.domain.Pageable pageable =
                org.springframework.data.domain.PageRequest.of(page, Math.min(size, 200));

        return auditLogService
                .searchPage(action, outcome, documentId, actorEmail, from, to, pageable)
                .map(this::convertToResponse);
    }

    @GetMapping("/audit/verify")
    public AuditVerificationResponse verifyAuditLogs() {
        return integrityService.verify();
    }

    /**
     * Summary counters for the admin dashboard. Denied attempts are surfaced
     * prominently because a cluster of them is the signal worth noticing.
     */
    @GetMapping("/audit/stats")
    public Map<String, Object> stats() {

        long denied = auditLogService.countDenied();
        AuditVerificationResponse verification = integrityService.verify();

        return Map.of(
                "deniedEvents", denied,
                "chainValid", verification.isValid(),
                "checkedEntries", verification.getCheckedEntries()
        );
    }

    @GetMapping("/audit/document/{documentId}")
    public ResponseEntity<List<AuditLogResponse>> getDocumentHistory(
            @PathVariable Long documentId) {

        return ResponseEntity.ok(auditLogService
                .search(null, null, documentId, null, null, null)
                .stream()
                .map(this::convertToResponse)
                .toList());
    }

    private AuditLogResponse convertToResponse(AuditLog log) {
        return new AuditLogResponse(
                log.getId(),
                log.getActorUserId(),
                log.getActorEmail(),
                log.getDocumentId(),
                log.getDocumentName(),
                log.getTargetUserId(),
                log.getTargetEmail(),
                log.getAction(),
                log.getOutcome(),
                log.getDetails(),
                log.getIpAddress(),
                log.getCreatedAt()
        );
    }
}
