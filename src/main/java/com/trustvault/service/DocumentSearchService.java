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

        if (actor.getRole() == com.trustvault.enums.Role.ADMIN) {
            String kw = blankToNull(query.keyword());
            String cat = blankToNull(query.category());
            String own = normalizeOwner(query.owner());

            List<Document> all = documentRepository.findAll(
                    org.springframework.data.domain.Sort.by(
                            org.springframework.data.domain.Sort.Direction.DESC, "uploadedAt", "id"));

            List<Document> filtered = all.stream()
                    .filter(d -> kw == null
                            || d.getFileName().toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT))
                            || d.getCategory().toLowerCase(Locale.ROOT).contains(kw.toLowerCase(Locale.ROOT)))
                    .filter(d -> cat == null || d.getCategory().equalsIgnoreCase(cat))
                    .filter(d -> own == null || d.getOwnerEmail().equalsIgnoreCase(own))
                    .filter(d -> query.from() == null || !d.getUploadedAt().isBefore(query.from()))
                    .filter(d -> query.to() == null || !d.getUploadedAt().isAfter(query.to()))
                    .toList();

            return documentListService.toResponses(filtered, actor);
        }

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
