package com.trustvault.controller;

import com.trustvault.dto.RoleChangeRequest;
import com.trustvault.dto.TemporaryPasswordResponse;
import com.trustvault.dto.UserResponse;
import com.trustvault.entity.User;
import com.trustvault.enums.Role;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.UserService;

import jakarta.validation.Valid;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * User administration. Role changes and password resets are audited, and an
 * administrator cannot act on their own account - otherwise a single
 * compromised admin session can silently lock the real owner out.
 */
@RestController
@RequestMapping("/api/admin/users")
@PreAuthorize("hasRole('ADMIN')")
public class AdminUserController {

    private final UserService userService;
    private final CurrentUserService currentUserService;

    public AdminUserController(UserService userService,
                               CurrentUserService currentUserService) {
        this.userService = userService;
        this.currentUserService = currentUserService;
    }

    @GetMapping
    public List<UserResponse> listUsers() {
        return userService.findAll();
    }

    @PatchMapping("/{userId}/role")
    public UserResponse changeRole(@PathVariable Long userId,
                                   @Valid @RequestBody RoleChangeRequest request,
                                   Authentication authentication) {

        User actor = currentUserService.require(authentication);

        return userService.changeRole(
                userId,
                Role.valueOf(request.getRole().trim().toUpperCase()),
                actor);
    }

    /**
     * Issues a temporary password for a user who cannot sign in.
     *
     * The value is returned once, to the administrator, and is never stored in
     * readable form. `no-store` keeps it out of shared caches, and it is
     * deliberately absent from the audit record.
     */
    @PostMapping("/{userId}/reset-password")
    public ResponseEntity<TemporaryPasswordResponse> resetPassword(
            @PathVariable Long userId,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);

        TemporaryPasswordResponse response =
                userService.resetPassword(userId, actor);

        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .header("Pragma", "no-cache")
                .body(response);
    }
}
