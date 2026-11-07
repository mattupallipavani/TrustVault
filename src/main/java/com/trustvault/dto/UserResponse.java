package com.trustvault.dto;

public class UserResponse {

    private final Long id;
    private final String fullName;
    private final String email;
    private final String role;
    private final boolean mustChangePassword;

    public UserResponse(Long id, String fullName, String email, String role, boolean mustChangePassword) {
        this.id = id;
        this.fullName = fullName;
        this.email = email;
        this.role = role;
        this.mustChangePassword = mustChangePassword;
    }

    public Long getId() {
        return id;
    }

    public String getFullName() {
        return fullName;
    }

    public String getEmail() {
        return email;
    }

    public String getRole() {
        return role;
    }

    public boolean isMustChangePassword() {
        return mustChangePassword;
    }
}
