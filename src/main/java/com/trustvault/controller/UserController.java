package com.trustvault.controller;

import com.trustvault.dto.UserResponse;
import com.trustvault.entity.User;
import com.trustvault.service.CurrentUserService;
import com.trustvault.service.UserService;

import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

/**
 * The caller's own identity, plus exact-match lookup for sharing.
 */
@RestController
@RequestMapping("/api/users")
public class UserController {

    private final UserService userService;
    private final CurrentUserService currentUserService;

    public UserController(UserService userService,
                          CurrentUserService currentUserService) {
        this.userService = userService;
        this.currentUserService = currentUserService;
    }

    @GetMapping("/me")
    public UserResponse me(Authentication authentication) {
        User actor = currentUserService.require(authentication);
        return userService.toResponse(actor);
    }

    /**
     * Turns an address into an id so an owner can grant access.
     *
     * Exact match only. A search endpoint here would let any account map the
     * whole directory, which is how a viewer learns who else works at the
     * company. Guessing addresses is still possible; that is listed as an
     * accepted risk in THREATS.md.
     */
    @GetMapping("/lookup")
    public UserResponse lookup(@RequestParam String email,
                               Authentication authentication) {
        currentUserService.require(authentication);
        return userService.lookupByEmail(email);
    }
}
