package com.trustvault.dto;

import java.time.LocalDateTime;

public class AuditLogResponse {

    private final Long id;
    private final Long actorUserId;
    private final String actorEmail;
    private final Long documentId;
    private final String documentName;
    private final Long targetUserId;
    private final String targetEmail;
    private final String action;
    private final String outcome;
    private final String details;
    private final String ipAddress;
    private final LocalDateTime createdAt;

    public AuditLogResponse(Long id, Long actorUserId, String actorEmail,
                            Long documentId, String documentName,
                            Long targetUserId, String targetEmail,
                            String action, String outcome, String details,
                            String ipAddress, LocalDateTime createdAt) {
        this.id = id;
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
        this.documentId = documentId;
        this.documentName = documentName;
        this.targetUserId = targetUserId;
        this.targetEmail = targetEmail;
        this.action = action;
        this.outcome = outcome;
        this.details = details;
        this.ipAddress = ipAddress;
        this.createdAt = createdAt;
    }

    public Long getId() {
        return id;
    }

    public Long getActorUserId() {
        return actorUserId;
    }

    public String getActorEmail() {
        return actorEmail;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public String getDocumentName() {
        return documentName;
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public String getTargetEmail() {
        return targetEmail;
    }

    public String getAction() {
        return action;
    }

    public String getOutcome() {
        return outcome;
    }

    public String getDetails() {
        return details;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }
}
