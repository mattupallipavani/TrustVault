package com.trustvault.service;

import com.trustvault.entity.Document;
import com.trustvault.entity.User;
import com.trustvault.repository.DocumentPermissionRepository;
import com.trustvault.repository.DocumentRepository;
import com.trustvault.util.AuditEvent;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.io.IOException;

/**
 * Owner-only deletion.
 *
 * Order matters and is the opposite of the naive version. The database is the
 * record of truth, so the row and its permission rows go first, the audit
 * entry commits with them, and only then is the file unlinked - after commit,
 * because deleting the file first and then failing to commit leaves a document
 * that exists in the UI but has no content, with no audit record either.
 *
 * If the unlink fails, the document is already gone and the failure is logged
 * and recorded; the orphan file is a cleanup job, not a correctness problem.
 */
@Service
public class DocumentDeleteService {

    private static final Logger log =
            LoggerFactory.getLogger(DocumentDeleteService.class);

    private final DocumentRepository documentRepository;
    private final DocumentPermissionRepository permissionRepository;
    private final StorageService storageService;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;

    public DocumentDeleteService(
            DocumentRepository documentRepository,
            DocumentPermissionRepository permissionRepository,
            StorageService storageService,
            PermissionService permissionService,
            AuditLogService auditLogService) {

        this.documentRepository = documentRepository;
        this.permissionRepository = permissionRepository;
        this.storageService = storageService;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
    }

    @Transactional
    public void deleteDocument(Long documentId, User actor) {

        Document document = permissionService.requireOwner(documentId, actor);

        String storageKey = document.getStorageKey();
        String fileName = document.getFileName();

        // Permission rows reference the document; leaving them behind either
        // blocks the delete or leaves grants pointing at nothing.
        permissionRepository.deleteAllForDocument(documentId);

        documentRepository.delete(document);

        // Commits with the delete, and snapshots the name so the record still
        // identifies the document after the row is gone.
        auditLogService.success(AuditEvent.by("DELETE", actor)
                .document(document)
                .details("Document deleted: " + fileName));

        deleteFileAfterCommit(storageKey, actor, documentId, fileName);
    }

    private void deleteFileAfterCommit(String storageKey, User actor,
                                       Long documentId, String fileName) {

        if (!TransactionSynchronizationManager.isSynchronizationActive()) {
            deleteFileQuietly(storageKey, actor, documentId, fileName);
            return;
        }

        TransactionSynchronizationManager.registerSynchronization(
                new TransactionSynchronization() {
                    @Override
                    public void afterCommit() {
                        deleteFileQuietly(storageKey, actor, documentId, fileName);
                    }
                });
    }

    private void deleteFileQuietly(String storageKey, User actor,
                                   Long documentId, String fileName) {
        try {
            storageService.delete(storageKey);
        } catch (IOException | RuntimeException exception) {

            log.error("Could not remove stored file for deleted document {}",
                    documentId, exception);

            try {
                auditLogService.standalone(
                        AuditEvent.by("STORAGE_CLEANUP_FAILURE", actor)
                                .documentId(documentId, fileName)
                                .details("File on disk could not be removed: "
                                        + exception.getClass().getSimpleName()),
                        "FAILURE");
            } catch (RuntimeException auditFailure) {
                log.error("Could not record storage cleanup failure", auditFailure);
            }
        }
    }
}
