package com.trustvault.controller;

import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.DocumentDeleteService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/documents")
public class DocumentDeleteController {

    private final DocumentDeleteService deleteService;
    private final CurrentUserService currentUserService;

    public DocumentDeleteController(DocumentDeleteService deleteService,
                                    CurrentUserService currentUserService) {
        this.deleteService = deleteService;
        this.currentUserService = currentUserService;
    }

    @DeleteMapping("/{documentId}")
    public ResponseEntity<Map<String, String>> deleteDocument(
            @PathVariable Long documentId,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);

        deleteService.deleteDocument(documentId, actor);

        return ResponseEntity.ok(Map.of(
                "message", "Document deleted successfully"
        ));
    }
}
