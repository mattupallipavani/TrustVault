package com.trustvault.service;

import com.trustvault.dto.PermissionResponse;
import com.trustvault.entity.Document;
import com.trustvault.entity.DocumentPermission;
import com.trustvault.entity.User;
import com.trustvault.enums.PermissionLevel;
import com.trustvault.exception.ResourceNotFoundException;
import com.trustvault.repository.DocumentPermissionRepository;
import com.trustvault.repository.DocumentRepository;
import com.trustvault.repository.UserRepository;
import com.trustvault.util.AuditEvent;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * THE authorization decision point.
 *
 * Every route that returns or changes document content calls one of the
 * {@code require*} methods below. If you are looking for where TrustVault
 * decides whether someone may read a file, it is
 * {@link #hasAccess(Document, Long, boolean)} and nowhere else.
 *
 * Why it is centralised: authorization spread across controllers is how these
 * systems leak. A new endpoint added later either calls this class or returns
 * nothing, because the services that own the file bytes only hand over data
 * they have been given a {@link Document} for by a {@code require*} call.
 *
 * Refusals are audited before the exception is thrown, in a separate
 * transaction, so the evidence outlives the rolled back request.
 */
@Service
public class PermissionService {

    private final DocumentRepository documentRepository;
    private final UserRepository userRepository;
    private final DocumentPermissionRepository permissionRepository;
    private final AuditLogService auditLogService;

    public PermissionService(
            DocumentRepository documentRepository,
            UserRepository userRepository,
            DocumentPermissionRepository permissionRepository,
            AuditLogService auditLogService) {

        this.documentRepository = documentRepository;
        this.userRepository = userRepository;
        this.permissionRepository = permissionRepository;
        this.auditLogService = auditLogService;
    }

    // ------------------------------------------------------------------
    // Enforcement - the only entry points the rest of the app should use.
    // ------------------------------------------------------------------

    public Document requireRead(Long documentId, User actor) {
        Document document = load(documentId);
        if (!hasAccess(document, actor.getId(), false)) {
            deny(actor, document, "VIEW",
                    "Attempted to read a document without a READ grant");
        }
        return document;
    }

    public Document requireEdit(Long documentId, User actor) {
        Document document = load(documentId);
        if (!hasAccess(document, actor.getId(), true)) {
            deny(actor, document, "EDIT",
                    "Attempted to modify a document without an EDIT grant");
        }
        return document;
    }

    public Document requireOwner(Long documentId, User actor) {
        Document document = load(documentId);
        if (!isOwner(document, actor.getId())) {
            deny(actor, document, "OWNER_ACTION",
                    "Attempted an owner-only action on a document they do not own");
        }
        return document;
    }

    // ------------------------------------------------------------------
    // Predicates
    // ------------------------------------------------------------------

    public boolean canRead(Long documentId, Long userId) {
        return documentRepository.findById(documentId)
                .map(document -> hasAccess(document, userId, false))
                .orElse(false);
    }

    public boolean canEdit(Long documentId, Long userId) {
        return documentRepository.findById(documentId)
                .map(document -> hasAccess(document, userId, true))
                .orElse(false);
    }

    public boolean isOwner(Long documentId, Long userId) {
        return documentRepository.findById(documentId)
                .map(document -> isOwner(document, userId))
                .orElse(false);
    }

    private boolean isOwner(Document document, Long userId) {
        return userId != null && userId.equals(document.getOwnerId());
    }

    /**
     * The one place READ/EDIT is decided.
     *
     * Owner always wins. Otherwise the stored grant is read on every single
     * request - there is no cache and no token - which is what makes
     * revocation immediate: the next request after the delete finds nothing.
     */
    private boolean hasAccess(Document document, Long userId, boolean editRequired) {

        if (userId == null) {
            return false;
        }

        if (isOwner(document, userId)) {
            return true;
        }

        return permissionRepository
                .findByDocumentIdAndUserId(document.getId(), userId)
                .map(DocumentPermission::getPermission)
                .map(this::parseLevel)
                .map(level -> editRequired
                        ? level.satisfiesEdit()
                        : level.satisfiesRead())
                .orElse(false);
    }

    /**
     * A stored value that is not READ or EDIT results in no access rather than
     * an exception: corrupt data must fail closed, not fail open, and must not
     * turn a request into a 500 that skips the check.
     */
    private PermissionLevel parseLevel(String raw) {
        try {
            return PermissionLevel.parse(raw);
        } catch (IllegalArgumentException exception) {
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Grant / revoke / list - owner only
    // ------------------------------------------------------------------

    @Transactional
    public void grantPermission(Long documentId, User actor,
                                Long targetUserId, String permissionType) {

        Document document = requireOwner(documentId, actor);
        PermissionLevel level = PermissionLevel.parse(permissionType);

        if (targetUserId == null) {
            throw new IllegalArgumentException("Target user is required");
        }

        if (targetUserId.equals(actor.getId())) {
            throw new IllegalArgumentException(
                    "The owner already has full access to this document");
        }

        User target = userRepository.findById(targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        DocumentPermission permission = permissionRepository
                .findByDocumentIdAndUserId(documentId, targetUserId)
                .orElseGet(DocumentPermission::new);

        permission.setDocument(document);
        permission.setUser(target);
        permission.setPermission(level.name());

        permissionRepository.save(permission);

        auditLogService.success(AuditEvent.by("GRANT_PERMISSION", actor)
                .document(document)
                .target(target)
                .details("Granted " + level.name()
                        + " to " + target.getEmail()));
    }

    @Transactional
    public void revokePermission(Long documentId, User actor, Long targetUserId) {

        Document document = requireOwner(documentId, actor);

        User target = userRepository.findById(targetUserId).orElse(null);

        int removed = permissionRepository
                .deleteForDocumentAndUser(documentId, targetUserId);

        auditLogService.success(AuditEvent.by("REVOKE_PERMISSION", actor)
                .document(document)
                .targetId(targetUserId,
                        target == null ? null : target.getEmail())
                .details(removed > 0
                        ? "Access revoked"
                        : "Revoke requested but the user had no grant"));
    }

    /**
     * Owner-only listing of the current grants.
     *
     * Read inside a transaction: {@code user} on a grant is a lazy association
     * and open-in-view is off, so mapping the response after the repository
     * call closes the session would throw LazyInitializationException - a 500
     * on the share panel the moment a document has at least one grant.
     */
    @Transactional(readOnly = true)
    public List<PermissionResponse> listPermissions(Long documentId, User actor) {

        Document document = requireOwner(documentId, actor);

        List<DocumentPermission> permissions =
                permissionRepository.findByDocumentId(document.getId());

        return permissions.stream()
                .map(permission -> new PermissionResponse(
                        document.getId(),
                        permission.getUser().getId(),
                        permission.getUser().getEmail(),
                        permission.getUser().getFullName(),
                        permission.getPermission(),
                        permission.getGrantedAt()))
                .toList();
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    private Document load(Long documentId) {
        if (documentId == null) {
            throw new ResourceNotFoundException("Document not found");
        }
        return documentRepository.findById(documentId)
                .orElseThrow(() -> new ResourceNotFoundException(
                        "Document not found"));
    }

    private void deny(User actor, Document document, String action,
                      String details) {

        auditLogService.denied(AuditEvent.by(action, actor)
                .document(document)
                .details(details));

        throw new AccessDeniedException("Access denied");
    }
}
