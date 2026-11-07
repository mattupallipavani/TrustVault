package com.trustvault.util;

import com.trustvault.entity.Document;
import com.trustvault.entity.User;

/**
 * Fluent description of one audit event.
 *
 * Exists so call sites read as what happened rather than as an eight argument
 * method call, where it is easy to put the target user in the actor slot and
 * record the wrong person.
 */
public final class AuditEvent {

    private final String action;
    private final Long actorUserId;
    private final String actorEmail;

    private Long documentId;
    private String documentName;
    private Long targetUserId;
    private String targetEmail;
    private String details;

    private AuditEvent(String action, Long actorUserId, String actorEmail) {
        this.action = action;
        this.actorUserId = actorUserId;
        this.actorEmail = actorEmail;
    }

    public static AuditEvent by(String action, User actor) {
        return new AuditEvent(
                action,
                actor == null ? null : actor.getId(),
                actor == null ? null : actor.getEmail()
        );
    }

    public static AuditEvent byEmail(String action, Long actorUserId, String actorEmail) {
        return new AuditEvent(action, actorUserId, actorEmail);
    }

    public static AuditEvent system(String action) {
        return new AuditEvent(action, null, null);
    }

    public AuditEvent document(Document document) {
        if (document != null) {
            this.documentId = document.getId();
            this.documentName = document.getFileName();
        }
        return this;
    }

    public AuditEvent documentId(Long documentId, String documentName) {
        this.documentId = documentId;
        this.documentName = documentName;
        return this;
    }

    public AuditEvent target(User user) {
        if (user != null) {
            this.targetUserId = user.getId();
            this.targetEmail = user.getEmail();
        }
        return this;
    }

    public AuditEvent targetId(Long targetUserId, String targetEmail) {
        this.targetUserId = targetUserId;
        this.targetEmail = targetEmail;
        return this;
    }

    public AuditEvent details(String details) {
        this.details = details;
        return this;
    }

    public String getAction() {
        return action;
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

    public String getDetails() {
        return details;
    }
}
