package com.trustvault.service;

import com.trustvault.entity.Document;
import com.trustvault.repository.DocumentRepository;
import com.trustvault.util.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

/**
 * Sweeps object storage and compares what is stored against what the database
 * says should be there.
 *
 * This is the check that catches silent corruption - bit rot, a half-written
 * object, an object deleted out of band, or bytes swapped on disk - none of
 * which the database alone can reveal. A download only ever validates the one
 * document someone asked for; this validates the archive.
 *
 * The shallow pass reads object metadata and compares size plus the SHA-256
 * recorded at upload (cheap, one stat per document). The deep pass re-reads
 * every object and recomputes its hash (expensive, reads everything).
 */
@Service
public class StorageIntegrityService {

    private static final Logger log = LoggerFactory.getLogger(StorageIntegrityService.class);

    /** Problems reported in full; the counts always cover every document. */
    private static final int MAX_REPORTED_ISSUES = 25;

    private final DocumentRepository documentRepository;
    private final StorageService storageService;
    private final AuditLogService auditLogService;

    public StorageIntegrityService(DocumentRepository documentRepository,
                                   StorageService storageService,
                                   AuditLogService auditLogService) {
        this.documentRepository = documentRepository;
        this.storageService = storageService;
        this.auditLogService = auditLogService;
    }

    public Report verify(boolean deep) {

        List<Document> documents = documentRepository.findAll();
        List<Issue> issues = new ArrayList<>();
        int missing = 0;
        int sizeMismatched = 0;
        int hashMismatched = 0;

        for (Document document : documents) {
            String storageKey = document.getStorageKey();

            if (storageKey == null || !storageService.exists(storageKey)) {
                missing++;
                add(issues, document, "Object is missing from storage");
                continue;
            }

            try {
                long storedSize = storageService.sizeOf(storageKey);

                if (document.getFileSize() != null && document.getFileSize() != storedSize) {
                    sizeMismatched++;
                    add(issues, document, "Size is " + storedSize + " bytes, expected "
                            + document.getFileSize());
                    continue;
                }

                String expected = document.getContentHash();
                if (expected == null) {
                    continue;
                }

                String actual = deep ? storageService.hashOf(storageKey)
                        : storageService.storedHash(storageKey);

                // A provider that keeps no metadata simply cannot answer a
                // shallow check; that is not evidence of corruption.
                if (actual != null && !expected.equals(actual)) {
                    hashMismatched++;
                    add(issues, document, "SHA-256 is " + actual + ", expected " + expected);
                }

            } catch (IOException e) {
                hashMismatched++;
                add(issues, document, "Could not be read: " + e.getMessage());
            }
        }

        int unhealthy = missing + sizeMismatched + hashMismatched;
        boolean healthy = unhealthy == 0;

        String summary = "Verified " + documents.size() + " document(s)"
                + (deep ? " (deep)" : " (metadata)")
                + ": " + (documents.size() - unhealthy) + " healthy, "
                + missing + " missing, " + sizeMismatched + " size mismatch, "
                + hashMismatched + " hash mismatch";

        if (healthy) {
            auditLogService.success(AuditEvent.system("STORAGE_VERIFIED").details(summary));
            log.info(summary);
        } else {
            auditLogService.failure(AuditEvent.system("STORAGE_VERIFIED")
                    .details(summary + "; first problem: "
                            + (issues.isEmpty() ? "none" : issues.get(0).problem())));
            log.warn(summary);
        }

        return new Report(documents.size(), documents.size() - unhealthy, missing,
                sizeMismatched, hashMismatched, deep, healthy, summary, issues);
    }

    private void add(List<Issue> issues, Document document, String problem) {
        if (issues.size() < MAX_REPORTED_ISSUES) {
            issues.add(new Issue(document.getId(), document.getFileName(), problem));
        }
    }

    public record Report(
            int checked,
            int healthy,
            int missing,
            int sizeMismatched,
            int hashMismatched,
            boolean deep,
            boolean allHealthy,
            String summary,
            List<Issue> issues
    ) {
    }

    public record Issue(Long documentId, String fileName, String problem) {
    }
}
