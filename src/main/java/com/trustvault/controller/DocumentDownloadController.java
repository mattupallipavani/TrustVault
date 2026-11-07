package com.trustvault.controller;

import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.DocumentDownloadService;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

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
}
