package com.trustvault.dto;

import java.time.LocalDateTime;

public class PermissionResponse {

    private final Long documentId;
    private final Long userId;
    private final String userEmail;
    private final String userFullName;
    private final String permissionType;
    private final LocalDateTime grantedAt;

    public PermissionResponse(Long documentId, Long userId, String userEmail,
                              String userFullName, String permissionType,
                              LocalDateTime grantedAt) {
        this.documentId = documentId;
        this.userId = userId;
        this.userEmail = userEmail;
        this.userFullName = userFullName;
        this.permissionType = permissionType;
        this.grantedAt = grantedAt;
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

    public String getPermissionType() {
        return permissionType;
    }

    public LocalDateTime getGrantedAt() {
        return grantedAt;
    }
}
