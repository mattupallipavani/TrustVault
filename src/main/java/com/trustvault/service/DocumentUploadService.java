package com.trustvault.service;

import com.trustvault.dto.DocumentUploadResponse;
import com.trustvault.entity.Document;
import com.trustvault.entity.User;
import com.trustvault.enums.Role;
import com.trustvault.repository.DocumentRepository;
import com.trustvault.util.AuditEvent;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@Service
public class DocumentUploadService {

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final FileValidationService fileValidationService;
    private final AuditLogService auditLogService;

    public DocumentUploadService(
            DocumentRepository documentRepository,
            StorageService storageService,
            FileValidationService fileValidationService,
            AuditLogService auditLogService) {

        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.fileValidationService = fileValidationService;
        this.auditLogService = auditLogService;
    }

    /**
     * Upload is limited by role. A VIEWER account exists to read what was
     * shared with it and cannot add content; that is enforced here rather than
     * by hiding the button.
     */
    @Transactional
    public DocumentUploadResponse uploadDocument(MultipartFile file,
                                                 String category,
                                                 User owner) throws IOException {

        if (owner.getRole() == Role.VIEWER) {
            auditLogService.denied(AuditEvent.by("UPLOAD", owner)
                    .details("VIEWER role cannot upload documents"));
            throw new AccessDeniedException("Viewers cannot upload documents");
        }

        String trimmedCategory = validateCategory(category);

        FileValidationService.ValidatedFile validated =
                fileValidationService.validate(file);

        String storageKey = java.util.UUID.randomUUID().toString();
        try (java.io.InputStream stream = file.getInputStream()) {
            // The hash travels with the object so a later download can verify
            // integrity from metadata instead of re-reading every byte.
            storageService.store(storageKey, stream, validated.size(),
                    validated.contentType(), validated.sha256());
        }

        try {
            Document document = new Document();
            document.setFileName(validated.fileName());
            document.setFileType(validated.contentType());
            document.setFileSize(validated.size());
            document.setOwnerId(owner.getId());
            document.setOwnerEmail(owner.getEmail());
            document.setCategory(trimmedCategory);
            document.setStorageKey(storageKey);
            document.setContentHash(validated.sha256());

            Document saved = documentRepository.save(document);

            auditLogService.success(AuditEvent.by("UPLOAD", owner)
                    .document(saved)
                    .details("Uploaded " + validated.fileName()
                            + " (" + validated.size() + " bytes)"));

            return new DocumentUploadResponse(
                    saved.getId(),
                    saved.getFileName(),
                    saved.getCategory(),
                    "Document uploaded successfully");

        } catch (RuntimeException exception) {

            // The row was not written, so the bytes must not be left behind.
            try {
                storageService.delete(storageKey);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }

            throw exception;
        }
    }

    private String validateCategory(String category) {

        if (category == null || category.isBlank()) {
            throw new IllegalArgumentException("Category is required");
        }

        String trimmed = category.trim();

        if (trimmed.length() > 50) {
            throw new IllegalArgumentException(
                    "Category cannot exceed 50 characters");
        }

        if (!trimmed.matches("[\\p{L}\\p{N} &._-]+")) {
            throw new IllegalArgumentException(
                    "Category may only contain letters, numbers, spaces and . _ & -");
        }

        return trimmed;
    }
}
