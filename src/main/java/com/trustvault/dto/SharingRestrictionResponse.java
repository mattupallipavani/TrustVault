package com.trustvault.dto;

import java.time.LocalDateTime;

/**
 * Response DTO for sharing restrictions visible to administrators.
 */
public class SharingRestrictionResponse {

    private final Long documentId;
    private final Long userId;
    private final String userEmail;
    private final String userFullName;
    private final String restrictedByEmail;
    private final LocalDateTime restrictedAt;

    public SharingRestrictionResponse(Long documentId, Long userId,
                                       String userEmail, String userFullName,
                                       String restrictedByEmail,
                                       LocalDateTime restrictedAt) {
        this.documentId = documentId;
        this.userId = userId;
        this.userEmail = userEmail;
        this.userFullName = userFullName;
        this.restrictedByEmail = restrictedByEmail;
        this.restrictedAt = restrictedAt;
    }

    public Long getDocumentId() {
        return documentId;
    }

    public Long getUserId() {
        return userId;
    }

    public String getUserEmail() {
        return userEmail;
    }

    public String getUserFullName() {
        return userFullName;
    }

    public String getRestrictedByEmail() {
        return restrictedByEmail;
    }

    public LocalDateTime getRestrictedAt() {
        return restrictedAt;
    }
}

