package com.trustvault.service;

import com.trustvault.entity.User;
import com.trustvault.repository.UserRepository;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;

/**
 * Resolves the authenticated principal to a database user.
 *
 * Every controller goes through this and passes the resulting {@link User}
 * downwards. No endpoint accepts a user id from the request to decide who is
 * acting: that is the classic horizontal privilege escalation, and the only
 * defence is never to accept the value at all.
 */
@Service
public class CurrentUserService {

    private final UserRepository userRepository;

    public CurrentUserService(UserRepository userRepository) {
        this.userRepository = userRepository;
    }

    public User require(Authentication authentication) {

        if (authentication == null || !authentication.isAuthenticated()) {
            throw new AccessDeniedException("Authentication required");
        }

        String email = authentication.getName();

        return userRepository.findByEmailIgnoreCase(email)
                .orElseThrow(() -> new AccessDeniedException(
                        "Authentication required"));
    }
}
