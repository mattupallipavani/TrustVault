package com.trustvault.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public class RoleChangeRequest {

    @NotBlank(message = "Role is required")
    @Pattern(regexp = "(?i)ADMIN|OWNER|VIEWER",
             message = "Role must be ADMIN, OWNER or VIEWER")
    private String role;

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }
}
