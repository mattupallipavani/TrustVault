package com.trustvault.dto;

import java.time.LocalDateTime;

public class DocumentListResponse {

    private final Long id;
    private final String fileName;
    private final String fileType;
    private final Long fileSize;
    private final String category;
    private final String ownerEmail;
    private final LocalDateTime uploadedAt;
    private final String accessLevel;

    public DocumentListResponse(Long id, String fileName, String fileType,
                                Long fileSize, String category,
                                String ownerEmail, LocalDateTime uploadedAt,
                                String accessLevel) {
        this.id = id;
        this.fileName = fileName;
        this.fileType = fileType;
        this.fileSize = fileSize;
        this.category = category;
        this.ownerEmail = ownerEmail;
        this.uploadedAt = uploadedAt;
        this.accessLevel = accessLevel;
    }

    public Long getId() {
        return id;
    }

    public String getFileName() {
        return fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public String getCategory() {
        return category;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    /**
     * OWNER, READ, EDIT or NONE, computed server side. The client uses it to
     * shape the UI only; it is never sent back as authorization.
     */
    public String getAccessLevel() {
        return accessLevel;
    }
}
