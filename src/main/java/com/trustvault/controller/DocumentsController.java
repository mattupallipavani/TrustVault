package com.trustvault.controller;

import com.trustvault.dto.DocumentListResponse;
import com.trustvault.dto.DocumentQuery;
import com.trustvault.dto.DocumentUpdateRequest;
import com.trustvault.entity.Document;
import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.DocumentListService;
import com.trustvault.service.DocumentSearchService;
import com.trustvault.service.DocumentUpdateService;
import com.trustvault.service.PermissionService;

import jakarta.validation.Valid;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.List;

@RestController
@RequestMapping("/api/documents")
public class DocumentsController {

    private final DocumentListService documentListService;
    private final DocumentSearchService documentSearchService;
    private final DocumentUpdateService documentUpdateService;
    private final PermissionService permissionService;
    private final CurrentUserService currentUserService;

    public DocumentsController(
            DocumentListService documentListService,
            DocumentSearchService documentSearchService,
            DocumentUpdateService documentUpdateService,
            PermissionService permissionService,
            CurrentUserService currentUserService) {

        this.documentListService = documentListService;
        this.documentSearchService = documentSearchService;
        this.documentUpdateService = documentUpdateService;
        this.permissionService = permissionService;
        this.currentUserService = currentUserService;
    }

    /**
     * Documents the caller may read. There is no "all documents" mode; the
     * query is scoped by the authenticated principal.
     */
    @GetMapping
    public List<DocumentListResponse> getDocuments(Authentication authentication) {
        User actor = currentUserService.require(authentication);
        return documentListService.list(actor);
    }

    /**
     * Paginated document listing for enterprise scalability.
     */
    @GetMapping("/paged")
    public Page<DocumentListResponse> getDocumentsPaged(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            Authentication authentication) {
        User actor = currentUserService.require(authentication);
        Pageable pageable = PageRequest.of(page, Math.min(size, 100));
        return documentListService.listPaged(actor, pageable);
    }

    /**
     * Search by name, category, owner and upload date.
     *
     * `scope` narrows to `owned` or `shared`; anything else means no scope
     * filter. Authorization is unaffected by the scope value.
     */
    @GetMapping("/search")
    public List<DocumentListResponse> searchDocuments(
            @RequestParam(required = false) String keyword,
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String owner,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime from,
            @RequestParam(required = false)
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME)
            LocalDateTime to,
            @RequestParam(required = false) String scope,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);

        return documentSearchService.search(
                new DocumentQuery(keyword, category, owner, from, to, scope),
                actor);
    }

    /**
     * Metadata for one document. Goes through requireRead, so an unreadable
     * document is a 403 even though only metadata is returned: a filename and
     * an owner are already confidential.
     */
    @GetMapping("/{documentId}")
    public DocumentListResponse getDocument(
            @PathVariable Long documentId,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);

        Document document = permissionService.requireRead(documentId, actor);

        return new DocumentListResponse(
                document.getId(),
                document.getFileName(),
                document.getFileType(),
                document.getFileSize(),
                document.getCategory(),
                document.getOwnerEmail(),
                document.getUploadedAt(),
                permissionService.isOwner(documentId, actor.getId())
                        ? "OWNER"
                        : "READ"
        );
    }

    /**
     * Metadata edit. Requires EDIT permission.
     */
    @PutMapping("/{documentId}")
    public DocumentListResponse updateDocument(
            @PathVariable Long documentId,
            @Valid @RequestBody DocumentUpdateRequest request,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);

        return documentUpdateService.updateDocument(
                documentId,
                actor,
                request.getFileName(),
                request.getCategory()
        );
    }
}
