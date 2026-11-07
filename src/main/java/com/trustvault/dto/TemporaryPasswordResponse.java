package com.trustvault.dto;

/**
 * Returned once to an administrator after a reset.
 *
 * The password is generated, hashed, stored, and returned in this response -
 * it is not persisted anywhere in readable form and cannot be retrieved again.
 */
public class TemporaryPasswordResponse {

    private final Long userId;
    private final String email;
    private final String temporaryPassword;
    private final String message;

    public TemporaryPasswordResponse(Long userId, String email,
                                     String temporaryPassword, String message) {
        this.userId = userId;
        this.email = email;
        this.temporaryPassword = temporaryPassword;
        this.message = message;
    }

    public Long getUserId() {
        return userId;
    }

    public String getEmail() {
        return email;
    }

    public String getTemporaryPassword() {
        return temporaryPassword;
    }

    public String getMessage() {
        return message;
    }
}
