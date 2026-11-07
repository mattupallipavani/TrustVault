package com.trustvault.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "documents", indexes = {
        @Index(name = "idx_documents_owner", columnList = "ownerId"),
        @Index(name = "idx_documents_uploaded_at", columnList = "uploadedAt")
})
public class Document {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, length = 255)
    private String fileName;

    @Column(nullable = false, length = 150)
    private String fileType;

    @Column(nullable = false)
    private Long fileSize;

    @Column(nullable = false)
    private Long ownerId;

    /**
     * Snapshot of the owner's email at upload time.
     *
     * Denormalised on purpose: audit and listing stay readable even if the
     * owning account is later removed, and search by owner avoids a join that
     * could be used to widen a query.
     */
    @Column(nullable = false, length = 150)
    private String ownerEmail;

    @Column(nullable = false, length = 50)
    private String category;

    /**
     * Opaque UUID assigned by the storage layer. This is the only value used
     * to build a path on disk; the user supplied name never touches the
     * filesystem.
     */
    @Column(nullable = false, unique = true, length = 100)
    private String storageKey;

    /**
     * SHA-256 of the stored bytes, captured at upload time. Lets the system
     * prove the file on disk is still the file that was uploaded.
     */
    @Column(length = 64)
    private String contentHash;

    @Column(nullable = false)
    private LocalDateTime uploadedAt;

    public Document() {
    }

    @PrePersist
    protected void onCreate() {
        if (uploadedAt == null) {
            uploadedAt = LocalDateTime.now();
        }
    }

    public Long getId() {
        return id;
    }

    /**
     * Only for tests that construct documents in memory. No request body or
     * query parameter is ever mapped onto this setter.
     */
    public void setId(Long id) {
        this.id = id;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public String getFileType() {
        return fileType;
    }

    public void setFileType(String fileType) {
        this.fileType = fileType;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public Long getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(Long ownerId) {
        this.ownerId = ownerId;
    }

    public String getOwnerEmail() {
        return ownerEmail;
    }

    public void setOwnerEmail(String ownerEmail) {
        this.ownerEmail = ownerEmail;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getStorageKey() {
        return storageKey;
    }

    public void setStorageKey(String storageKey) {
        this.storageKey = storageKey;
    }

    public String getContentHash() {
        return contentHash;
    }

    public void setContentHash(String contentHash) {
        this.contentHash = contentHash;
    }

    public LocalDateTime getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(LocalDateTime uploadedAt) {
        this.uploadedAt = uploadedAt;
    }
}
