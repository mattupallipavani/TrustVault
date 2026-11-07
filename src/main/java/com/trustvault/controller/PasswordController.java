package com.trustvault.controller;

import com.trustvault.dto.ChangePasswordRequest;
import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.UserService;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

/**
 * Self-service password management.
 *
 * Separate from {@link AuthController} because changing your own password is
 * an authenticated action, while the auth controller handles unauthenticated
 * flows (login, registration, forgot-password).
 */
@RestController
@RequestMapping("/api/users/me")
public class PasswordController {

    private final UserService userService;
    private final CurrentUserService currentUserService;

    public PasswordController(UserService userService,
                              CurrentUserService currentUserService) {
        this.userService = userService;
        this.currentUserService = currentUserService;
    }

    /**
     * Change the authenticated user's own password.
     *
     * Also clears the {@code mustChangePassword} flag if it was set by an
     * administrator via {@code POST /api/admin/users/{id}/reset-password}.
     */
    @PostMapping("/change-password")
    public ResponseEntity<Map<String, String>> changePassword(
            @Valid @RequestBody ChangePasswordRequest request,
            Authentication authentication) {

        User actor = currentUserService.require(authentication);
        userService.changeOwnPassword(actor, request.getCurrentPassword(), request.getNewPassword());

        return ResponseEntity.ok(Map.of("message", "Password changed successfully"));
    }
}

