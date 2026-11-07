package com.trustvault.service;

import com.trustvault.dto.DocumentListResponse;
import com.trustvault.dto.DocumentQuery;
import com.trustvault.entity.Document;
import com.trustvault.entity.User;
import com.trustvault.repository.DocumentRepository;

import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Locale;

/**
 * Search by name, category, owner and upload date, over the caller's readable
 * set only.
 *
 * The owner filter is matched against the denormalised owner snapshot rather
 * than by accepting a user id, so a caller cannot use the search box to probe
 * documents owned by an arbitrary account: filtering by an owner you were not
 * granted simply returns nothing.
 */
@Service
public class DocumentSearchService {

    private final DocumentRepository documentRepository;
    private final DocumentListService documentListService;

    public DocumentSearchService(DocumentRepository documentRepository,
                                 DocumentListService documentListService) {
        this.documentRepository = documentRepository;
        this.documentListService = documentListService;
    }

    public List<DocumentListResponse> search(DocumentQuery query, User actor) {

        boolean ownedOnly = "owned".equalsIgnoreCase(query.scope());
        boolean sharedOnly = "shared".equalsIgnoreCase(query.scope());

        List<Document> matches = documentRepository.searchAccessible(
                actor.getId(),
                blankToNull(query.keyword()),
                blankToNull(query.category()),
                normalizeOwner(query.owner()),
                query.from(),
                query.to(),
                ownedOnly,
                sharedOnly
        );

        return documentListService.toResponses(matches, actor);
    }

    private String normalizeOwner(String owner) {
        String value = blankToNull(owner);
        return value == null ? null : value.toLowerCase(Locale.ROOT);
    }

    private String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
