package com.trustvault.controller;

import com.trustvault.dto.PermissionRequest;
import com.trustvault.dto.PermissionResponse;
import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.PermissionService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/permissions")
public class PermissionController {

    private final PermissionService permissionService;
    private final CurrentUserService currentUserService;

    public PermissionController(PermissionService permissionService,
                                CurrentUserService currentUserService) {
        this.permissionService = permissionService;
        this.currentUserService = currentUserService;
    }

    @PostMapping("/grant/{documentId}")
    public ResponseEntity<Map<String, String>> grantPermission(
            @PathVariable Long documentId,
            @Valid @RequestBody PermissionRequest request,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);

        permissionService.grantPermission(
                documentId,
                actor,
                request.getTargetUserId(),
                request.getPermissionType());

        return ResponseEntity.ok(Map.of(
                "message", "Permission granted successfully"
        ));
    }

    @DeleteMapping("/revoke/{documentId}/{targetUserId}")
    public ResponseEntity<Map<String, String>> revokePermission(
            @PathVariable Long documentId,
            @PathVariable Long targetUserId,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);

        permissionService.revokePermission(documentId, actor, targetUserId);

        return ResponseEntity.ok(Map.of(
                "message", "Permission revoked successfully"
        ));
    }

    /**
     * Who can currently read or edit this document. Owner only: a viewer
     * learning who else has access is itself a disclosure.
     */
    @GetMapping("/{documentId}")
    public List<PermissionResponse> getPermissions(
            @PathVariable Long documentId,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);

        return permissionService.listPermissions(documentId, actor);
    }
}
