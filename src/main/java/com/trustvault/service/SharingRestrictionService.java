package com.trustvault.service;

import com.trustvault.dto.SharingRestrictionResponse;
import com.trustvault.entity.Document;
import com.trustvault.entity.SharingRestriction;
import com.trustvault.entity.User;
import com.trustvault.exception.ResourceNotFoundException;
import com.trustvault.repository.DocumentRepository;
import com.trustvault.repository.SharingRestrictionRepository;
import com.trustvault.repository.UserRepository;
import com.trustvault.util.AuditEvent;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Manages sharing restrictions that administrators impose on users.
 *
 * A restriction prevents a user from granting permissions (sharing) on a
 * specific document. The user may still read or edit the document if they
 * already have a grant, but they cannot share it further.
 */
@Service
public class SharingRestrictionService {

    private final SharingRestrictionRepository restrictionRepository;
    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public SharingRestrictionService(
            SharingRestrictionRepository restrictionRepository,
            DocumentRepository documentRepository,
            UserRepository userRepository,
            AuditLogService auditLogService) {

        this.restrictionRepository = restrictionRepository;
        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Check whether a user is restricted from sharing a document.
     */
    public boolean isRestricted(Long documentId, Long userId) {
        return restrictionRepository.existsByDocumentIdAndRestrictedUserId(
                documentId, userId);
    }

    /**
     * List all restrictions for a document.
     */
    @Transactional(readOnly = true)
    public List<SharingRestrictionResponse> listRestrictions(Long documentId) {
        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document not found"));

        return restrictionRepository.findByDocumentId(document.getId())
                .stream()
                .map(r -> new SharingRestrictionResponse(
                        document.getId(),
                        r.getRestrictedUser().getId(),
                        r.getRestrictedUser().getEmail(),
                        r.getRestrictedUser().getFullName(),
                        r.getRestrictedByEmail(),
                        r.getRestrictedAt()))
                .toList();
    }

    /**
     * Apply a sharing restriction on a target user for a document.
     */
    @Transactional
    public void applyRestriction(Long documentId, Long targetUserId,
                                  User admin) {

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document not found"));

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "User not found"));

        // Already restricted? Nothing to do.
        if (restrictionRepository.existsByDocumentIdAndRestrictedUserId(
                documentId, targetUserId)) {
            return;
        }

        SharingRestriction restriction = new SharingRestriction();
        restriction.setDocument(document);
        restriction.setRestrictedUser(target);
        restriction.setRestrictedById(admin.getId());
        restriction.setRestrictedByEmail(admin.getEmail());

        restrictionRepository.save(restriction);

        auditLogService.success(AuditEvent.by("SHARING_RESTRICTED", admin)
                .document(document)
                .target(target)
                .details("Administrator restricted "
                        + target.getEmail()
                        + " from sharing document \""
                        + document.getFileName() + "\""));
    }

    /**
     * Remove a sharing restriction, allowing the user to share again.
     */
    @Transactional
    public void removeRestriction(Long documentId, Long targetUserId,
                                   User admin) {

        Document document = documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document not found"));

        User target = userRepository.findById(targetUserId).orElse(null);

        int removed = restrictionRepository.deleteByDocumentIdAndUserId(
                documentId, targetUserId);

        String targetEmail = target != null ? target.getEmail()
                : "user#" + targetUserId;

        auditLogService.success(AuditEvent.by("SHARING_UNRESTRICTED", admin)
                .document(document)
                .targetId(targetUserId, targetEmail)
                .details(removed > 0
                        ? "Administrator lifted sharing restriction for "
                                + targetEmail
                        : "Restriction removal requested but user had "
                                + "no restriction"));
    }
}

