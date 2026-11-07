package com.trustvault.controller;

import com.trustvault.dto.DocumentUploadResponse;
import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.DocumentUploadService;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;

@RestController
@RequestMapping("/api/documents")
public class DocumentUploadController {

    private final DocumentUploadService uploadService;
    private final CurrentUserService currentUserService;

    public DocumentUploadController(DocumentUploadService uploadService,
                                    CurrentUserService currentUserService) {
        this.uploadService = uploadService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/upload")
    public ResponseEntity<DocumentUploadResponse> uploadDocument(
            @RequestParam("file") MultipartFile file,
            @RequestParam("category") String category,
            Authentication authentication) throws IOException {

        User actor = currentUserService.require(authentication);

        DocumentUploadResponse response =
                uploadService.uploadDocument(file, category, actor);

        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }
}
