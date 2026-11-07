package com.trustvault.service;

import com.trustvault.dto.RegisterRequest;
import com.trustvault.dto.TemporaryPasswordResponse;
import com.trustvault.dto.UserResponse;
import com.trustvault.entity.User;
import com.trustvault.enums.Role;
import com.trustvault.exception.ConflictException;
import com.trustvault.exception.ResourceNotFoundException;
import com.trustvault.repository.UserRepository;
import com.trustvault.util.AuditEvent;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.util.List;
import java.util.Locale;

@Service
public class UserService {

    /**
     * Ambiguous characters (0/O, 1/l/I) are left out so a temporary password
     * can be read aloud without transcription errors.
     *
     * Reserved characters (&, %, #, $, +) are also left out: an administrator
     * has to relay this value, and those characters break as soon as the
     * password is pasted into a form body, a URL or a shell command. The set
     * below still gives roughly 94 bits at length 16.
     */
    private static final char[] TEMP_ALPHABET =
            "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789!@*-_"
                    .toCharArray();

    private static final int TEMP_PASSWORD_LENGTH = 16;

    private final SecureRandom secureRandom = new SecureRandom();

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final AuditLogService auditLogService;

    public UserService(UserRepository userRepository,
                       PasswordEncoder passwordEncoder,
                       AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.auditLogService = auditLogService;
    }

    /**
     * Self-service registration always creates an account that may own
     * documents. It never accepts a role from the request: a client that could
     * choose its own role could register as ADMIN. Promotion and demotion go
     * through {@link #changeRole}, which is admin-only and audited.
     */
    @Transactional
    public User registerUser(RegisterRequest request) {

        String email = request.getEmail().trim().toLowerCase(Locale.ROOT);

        if (userRepository.existsByEmail(email)) {
            throw new ConflictException("Email is already registered");
        }

        User user = new User();
        user.setFullName(request.getFullName().trim());
        user.setEmail(email);
        user.setPasswordHash(passwordEncoder.encode(request.getPassword()));
        user.setRole(Role.OWNER);

        return userRepository.save(user);
    }

    /**
     * Records a password reset request.
     *
     * The caller always receives the same confirmation, so this endpoint cannot
     * be used to discover whether an address has an account. An unknown
     * address simply produces no audit entry, because there is no account to
     * attribute it to.
     */
    @Transactional
    public void requestPasswordReset(String email) {

        String normalized = email == null
                ? ""
                : email.trim().toLowerCase(Locale.ROOT);

        userRepository.findByEmail(normalized).ifPresent(user ->
                auditLogService.success(
                        AuditEvent.by("PASSWORD_RESET_REQUESTED", user)
                                .target(user)
                                .details("Reset requested from the sign-in page")));
    }

    /**
     * Issues a one-time temporary password. Admin only, audited, and the
     * password itself never reaches the audit trail.
     */
    @Transactional
    public TemporaryPasswordResponse resetPassword(Long userId, User actor) {

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (target.getId().equals(actor.getId())) {
            throw new IllegalArgumentException(
                    "Use your own account settings to change your password");
        }

        String temporaryPassword = generateTemporaryPassword();

        target.setPasswordHash(passwordEncoder.encode(temporaryPassword));
        userRepository.save(target);

        auditLogService.success(AuditEvent.by("PASSWORD_RESET", actor)
                .target(target)
                .details("Administrator issued a temporary password"));

        return new TemporaryPasswordResponse(
                target.getId(),
                target.getEmail(),
                temporaryPassword,
                "Temporary password issued. It will not be shown again.");
    }

    private String generateTemporaryPassword() {

        StringBuilder builder = new StringBuilder(TEMP_PASSWORD_LENGTH);

        for (int index = 0; index < TEMP_PASSWORD_LENGTH; index++) {
            builder.append(TEMP_ALPHABET[
                    secureRandom.nextInt(TEMP_ALPHABET.length)]);
        }

        return builder.toString();
    }

    public User requireByEmail(String email) {
        return userRepository.findByEmail(email)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));
    }

    /**
     * Exact-match lookup used by the sharing screen.
     *
     * Deliberately not a "list all users" endpoint: that would let any account
     * harvest the company directory. You can confirm an address you already
     * know, and nothing else.
     */
    public UserResponse lookupByEmail(String email) {

        if (email == null || email.isBlank()) {
            throw new IllegalArgumentException("Email is required");
        }

        User user = userRepository.findByEmail(email.trim().toLowerCase(Locale.ROOT))
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        return toResponse(user);
    }

    public List<UserResponse> findAll() {
        return userRepository.findAll().stream()
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public UserResponse changeRole(Long userId, Role newRole, User actor) {

        if (newRole == null) {
            throw new IllegalArgumentException("Role is required");
        }

        User target = userRepository.findById(userId)
                .orElseThrow(() -> new ResourceNotFoundException("User not found"));

        if (target.getId().equals(actor.getId())) {
            throw new IllegalArgumentException(
                    "You cannot change your own role");
        }

        Role previous = target.getRole();
        target.setRole(newRole);
        userRepository.save(target);

        auditLogService.success(AuditEvent.by("ROLE_CHANGE", actor)
                .target(target)
                .details("Role changed from " + previous + " to " + newRole));

        return toResponse(target);
    }

    public UserResponse toResponse(User user) {
        return new UserResponse(
                user.getId(),
                user.getFullName(),
                user.getEmail(),
                user.getRole().name());
    }
}
