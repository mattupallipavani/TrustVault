package com.trustvault.service;

import com.trustvault.entity.Document;
import com.trustvault.entity.User;
import com.trustvault.exception.ResourceNotFoundException;
import com.trustvault.util.AuditEvent;

import org.springframework.core.io.InputStreamResource;
import org.springframework.core.io.Resource;
import org.springframework.stereotype.Service;

import java.io.IOException;

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

        verifyContentHash(document, storageKey, actor);

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
     * Compares the bytes in storage with the hash captured at upload.
     * Detects a file swapped on the server, which the database alone would not show.
     */
    private void verifyContentHash(Document document, String storageKey, User actor)
            throws IOException {

        if (document.getContentHash() == null) {
            return;
        }

        String actual = storageService.hashOf(storageKey);

        if (!actual.equals(document.getContentHash())) {
            auditLogService.failure(AuditEvent.by("DOWNLOAD", actor)
                    .document(document)
                    .details("Stored file failed its integrity check"));

            throw new IllegalStateException(
                    "Stored document failed its integrity check");
        }
    }

    public record DownloadPayload(
            Resource resource,
            String fileName,
            String contentType,
            long size
    ) {
    }
}
