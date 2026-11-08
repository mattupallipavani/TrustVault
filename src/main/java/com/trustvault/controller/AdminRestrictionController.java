package com.trustvault.controller;

import com.trustvault.dto.SharingRestrictionResponse;
import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.SharingRestrictionService;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

/**
 * Admin-only endpoints for managing sharing restrictions.
 *
 * A sharing restriction prevents a specific user from granting permissions
 * (sharing) on a specific document. The user may still read/edit the document
 * if they already have access, but they cannot share it further.
 */
@RestController
@RequestMapping("/api/admin/restrictions")
@PreAuthorize("hasRole('ADMIN')")
public class AdminRestrictionController {

    private final SharingRestrictionService restrictionService;
    private final CurrentUserService currentUserService;

    public AdminRestrictionController(SharingRestrictionService restrictionService,
                                       CurrentUserService currentUserService) {
        this.restrictionService = restrictionService;
        this.currentUserService = currentUserService;
    }

    /**
     * List all sharing restrictions for a document.
     */
    @GetMapping("/{documentId}")
    public List<SharingRestrictionResponse> listRestrictions(
            @PathVariable Long documentId,
            Authentication authentication) {

        currentUserService.require(authentication);
        return restrictionService.listRestrictions(documentId);
    }

    /**
     * Apply a sharing restriction: prevent the target user from sharing
     * (granting permissions on) this document.
     */
    @PostMapping("/{documentId}/{targetUserId}")
    public ResponseEntity<Map<String, String>> applyRestriction(
            @PathVariable Long documentId,
            @PathVariable Long targetUserId,
            Authentication authentication) {

        User admin = currentUserService.require(authentication);
        restrictionService.applyRestriction(documentId, targetUserId, admin);

        return ResponseEntity.ok(Map.of(
                "message", "Sharing restriction applied successfully"
        ));
    }

    /**
     * Remove a sharing restriction: allow the target user to share
     * this document again.
     */
    @DeleteMapping("/{documentId}/{targetUserId}")
    public ResponseEntity<Map<String, String>> removeRestriction(
            @PathVariable Long documentId,
            @PathVariable Long targetUserId,
            Authentication authentication) {

        User admin = currentUserService.require(authentication);
        restrictionService.removeRestriction(documentId, targetUserId, admin);

        return ResponseEntity.ok(Map.of(
                "message", "Sharing restriction removed successfully"
        ));
    }
}

