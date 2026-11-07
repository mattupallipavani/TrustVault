package com.trustvault.service;

import com.trustvault.dto.DocumentListResponse;
import com.trustvault.entity.Document;
import com.trustvault.entity.User;
import com.trustvault.repository.DocumentRepository;
import com.trustvault.util.AuditEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class DocumentUpdateService {

    private final DocumentRepository documentRepository;
    private final PermissionService permissionService;
    private final AuditLogService auditLogService;
    private final FileValidationService fileValidationService;

    public DocumentUpdateService(
            DocumentRepository documentRepository,
            PermissionService permissionService,
            AuditLogService auditLogService,
            FileValidationService fileValidationService) {

        this.documentRepository = documentRepository;
        this.permissionService = permissionService;
        this.auditLogService = auditLogService;
        this.fileValidationService = fileValidationService;
    }

    /**
     * Metadata edit. Requires EDIT, which the owner implicitly holds.
     *
     * Note what is not editable here: the file bytes, the owner and the
     * storage key. A document's content is replaced by uploading a new
     * document with its own audit trail, not by mutating this row.
     */
    @Transactional
    public DocumentListResponse updateDocument(Long documentId, User actor,
                                               String fileName, String category) {

        Document document = permissionService.requireEdit(documentId, actor);

        String safeName = fileValidationService.sanitizeFileName(fileName);

        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Category is required");
        }

        String trimmedCategory = category.trim();

        if (trimmedCategory.length() > 50) {
            throw new IllegalArgumentException("Category cannot exceed 50 characters");
        }

        String previousName = document.getFileName();

        document.setFileName(safeName);
        document.setCategory(trimmedCategory);

        Document updated = documentRepository.save(document);

        auditLogService.success(AuditEvent.by("UPDATE", actor)
                .document(updated)
                .details("Renamed from '" + previousName + "' to '"
                        + safeName + "', category '" + trimmedCategory + "'"));

        return new DocumentListResponse(
                updated.getId(),
                updated.getFileName(),
                updated.getFileType(),
                updated.getFileSize(),
                updated.getCategory(),
                updated.getOwnerEmail(),
                updated.getUploadedAt(),
                updated.getOwnerId().equals(actor.getId()) ? "OWNER" : "EDIT"
        );
    }
}
