package com.trustvault.service;

import com.trustvault.entity.Document;
import com.trustvault.entity.User;
import com.trustvault.exception.ResourceNotFoundException;
import com.trustvault.util.AuditEvent;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;

/**
 * Hands over file bytes - and only after {@link PermissionService} has said yes.
 *
 * This is the class an interviewer will point at. The single line that decides
 * whether a file is reachable is the {@code requireRead} call below. Remove it
 * and every authenticated user can fetch every document by guessing an
 * integer: the integration test
 * {@code AuthorizationIntegrationTest#otherUserCannotDownloadDocument} fails
 * immediately, which is the point of that test existing.
 */
@Service
public class DocumentDownloadService {

    private final StorageService storageService;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;

    public DocumentDownloadService(
            StorageService storageService,
            PermissionService permissionService,
            AuditLogService auditLogService) {

        this.storageService = storageService;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
    }

    public DownloadPayload downloadDocument(Long documentId, User actor)
            throws IOException {

        // 1. Authorization. Nothing below this line runs for a refused caller.
        Document document = permissionService.requireRead(documentId, actor);

        String storageKey = document.getStorageKey();

        if (!storageService.exists(storageKey)) {
            auditLogService.failure(AuditEvent.by("DOWNLOAD", actor)
                    .document(document)
                    .details("Stored file is missing from storage"));

            // 404 with a usable message rather than an opaque 500: the row
            // exists but the bytes do not, and the reader can act on that.
            throw new ResourceNotFoundException(
                    "The stored file for this document is missing");
        }

        verifyIntegrity(document, storageKey, actor);

        auditLogService.success(AuditEvent.by("DOWNLOAD", actor)
                .document(document)
                .details("Document downloaded"));

        long size = storageService.sizeOf(storageKey);

        return new DownloadPayload(
                new InputStreamResource(storageService.retrieve(storageKey)),
                document.getFileName(),
                document.getFileType(),
                size
        );
    }

    /**
     * Issues a short-lived direct URL, but only after the same authorization
     * and integrity checks a normal download performs.
     *
     * The returned URL is a bearer credential: anyone holding it can read that
     * one object until it expires, without a session and without another audit
     * row. That is why the lifetime is bounded by the storage provider and the
     * issuance - not the eventual fetch - is what the audit trail records.
     */
    public PresignedDownload presignDownload(Long documentId, User actor, Duration ttl)
            throws IOException {

        if (!storageService.supportsPresignedDownloads()) {
            throw new UnsupportedOperationException(
                    "This storage backend cannot issue direct download links");
        }

        Document document = permissionService.requireRead(documentId, actor);
        String storageKey = document.getStorageKey();

        if (!storageService.exists(storageKey)) {
            auditLogService.failure(AuditEvent.by("DOWNLOAD_LINK_ISSUED", actor)
                    .document(document)
                    .details("Stored file is missing from storage"));
            throw new ResourceNotFoundException(
                    "The stored file for this document is missing");
        }

        verifyIntegrity(document, storageKey, actor);

        String url = storageService.presignedDownloadUrl(storageKey, ttl);

        auditLogService.success(AuditEvent.by("DOWNLOAD_LINK_ISSUED", actor)
                .document(document)
                .details("Issued direct download link valid for " + ttl.toSeconds() + "s"));

        return new PresignedDownload(url, ttl.toSeconds());
    }

    /**
     * Two checks, cheapest first.
     *
     * Size and the SHA-256 recorded as object metadata are read from a single
     * stat, so the common path detects a swapped or truncated object without
     * pulling the bytes down again. Providers that keep no metadata fall back
     * to re-hashing the object, which is correct but costs a full read.
     */
    private void verifyIntegrity(Document document, String storageKey, User actor)
            throws IOException {

        long storedSize = storageService.sizeOf(storageKey);

        if (document.getFileSize() != null && document.getFileSize() != storedSize) {
            failIntegrity(document, actor,
                    "Stored file size " + storedSize + " does not match recorded "
                            + document.getFileSize());
        }

        if (document.getContentHash() == null) {
            return;
        }

        String metadataHash = storageService.storedHash(storageKey);

        if (metadataHash != null) {
            if (!metadataHash.equals(document.getContentHash())) {
                failIntegrity(document, actor,
                        "Stored file hash does not match the hash recorded at upload");
            }
            return;
        }

        String actual = storageService.hashOf(storageKey);

        if (!actual.equals(document.getContentHash())) {
            failIntegrity(document, actor, "Stored file failed its integrity check");
        }
    }

    private void failIntegrity(Document document, User actor, String detail) {
        auditLogService.failure(AuditEvent.by("DOWNLOAD", actor)
                .document(document)
                .details(detail));

        throw new IllegalStateException("Stored document failed its integrity check");
    }

    public record DownloadPayload(
            Resource resource,
            String fileName,
            String contentType,
            long size
    ) {
    }

    public record PresignedDownload(String url, long expiresInSeconds) {
    }
}
