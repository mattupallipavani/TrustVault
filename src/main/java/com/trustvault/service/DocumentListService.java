package com.trustvault.service;

import com.trustvault.dto.DocumentListResponse;
import com.trustvault.entity.Document;
import com.trustvault.entity.DocumentPermission;
import com.trustvault.entity.User;
import com.trustvault.repository.DocumentPermissionRepository;
import com.trustvault.repository.DocumentRepository;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Lists what a user may read.
 *
 * The database query already excludes documents the user was not granted, so
 * this class cannot accidentally return one by forgetting a filter. Access
 * level is resolved from a single permission lookup for the whole page rather
 * than one query per row.
 */
@Service
public class DocumentListService {

    private final DocumentRepository documentRepository;
    private final DocumentPermissionRepository permissionRepository;

    public DocumentListService(
            DocumentRepository documentRepository,
            DocumentPermissionRepository permissionRepository) {

        this.documentRepository = documentRepository;
        this.permissionRepository = permissionRepository;
    }

    public List<DocumentListResponse> list(User actor) {
        List<Document> documents =
                documentRepository.findAccessibleByUser(actor.getId());
        return toResponses(documents, actor);
    }

    public Page<DocumentListResponse> listPaged(User actor, Pageable pageable) {
        Page<Document> documents =
                documentRepository.findAccessibleByUserPage(actor.getId(), pageable);

        Map<Long, String> grantedLevels = new HashMap<>();
        for (DocumentPermission permission : permissionRepository.findByUserId(actor.getId())) {
            grantedLevels.put(
                    permission.getDocument().getId(),
                    permission.getPermission());
        }

        return documents.map(document -> toResponse(document, actor, grantedLevels));
    }

    public List<DocumentListResponse> toResponses(List<Document> documents,
                                                  User actor) {

        Map<Long, String> grantedLevels = new HashMap<>();

        for (DocumentPermission permission
                : permissionRepository.findByUserId(actor.getId())) {
            grantedLevels.put(
                    permission.getDocument().getId(),
                    permission.getPermission());
        }

        return documents.stream()
                .map(document -> toResponse(document, actor, grantedLevels))
                .toList();
    }

    private DocumentListResponse toResponse(Document document, User actor,
                                            Map<Long, String> grantedLevels) {

        String accessLevel;

        if (document.getOwnerId().equals(actor.getId())) {
            accessLevel = "OWNER";
        } else {
            accessLevel = grantedLevels.getOrDefault(document.getId(), "NONE");
        }

        return new DocumentListResponse(
                document.getId(),
                document.getFileName(),
                document.getFileType(),
                document.getFileSize(),
                document.getCategory(),
                document.getOwnerEmail(),
                document.getUploadedAt(),
                accessLevel
        );
    }
}
