package com.trustvault.controller;

import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.DocumentDownloadService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentDownloadController {

    private final DocumentDownloadService downloadService;
    private final CurrentUserService currentUserService;

    public DocumentDownloadController(DocumentDownloadService downloadService,
                                      CurrentUserService currentUserService) {
        this.downloadService = downloadService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/{documentId}/download")
    public ResponseEntity<org.springframework.core.io.Resource> downloadDocument(
            @PathVariable Long documentId,
            Authentication authentication) throws IOException {

        User actor = currentUserService.require(authentication);

        DocumentDownloadService.DownloadPayload payload =
                downloadService.downloadDocument(documentId, actor);

        // Always attachment, never inline: serving a stored file inline lets
        // an HTML or SVG payload execute in the application's origin.
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(payload.fileName(), StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .contentType(MediaType.parseMediaType(payload.contentType()))
                .contentLength(payload.size())
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .header("X-Content-Type-Options", "nosniff")
                .body(payload.resource());
    }

    /**
     * Issues a short-lived direct link to object storage.
     *
     * Authorization happens here, once - which is exactly why the lifetime is
     * deliberately short and why the issuance is audited. Anything that grants
     * access without another permission check has to be assumed to leak.
     */
    @PostMapping("/{documentId}/download-url")
    public ResponseEntity<?> createDownloadUrl(
            @PathVariable Long documentId,
            Authentication authentication) throws IOException {

        User actor = currentUserService.require(authentication);

        try {
            DocumentDownloadService.PresignedDownload link =
                    downloadService.presignDownload(documentId, actor, Duration.ofSeconds(60));

            return ResponseEntity.ok()
                    .header(HttpHeaders.CACHE_CONTROL, "no-store")
                    .body(Map.of(
                            "url", link.url(),
                            "expiresInSeconds", link.expiresInSeconds()));

        } catch (UnsupportedOperationException e) {
            // Local filesystem storage has no equivalent; say so plainly
            // instead of pretending the feature exists.
            return ResponseEntity.status(HttpStatus.NOT_IMPLEMENTED)
                    .body(Map.of("message", "Direct download links require the MinIO backend"));
        }
    }
}
