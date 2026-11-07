package com.trustvault.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

public class PermissionRequest {

    @NotNull(message = "Target user ID is required")
    private Long targetUserId;

    @NotBlank(message = "Permission type is required")
    @Pattern(
        regexp = "READ|EDIT",
        message = "Permission must be READ or EDIT"
    )
    private String permissionType;

    public PermissionRequest() {
    }

    public Long getTargetUserId() {
        return targetUserId;
    }

    public void setTargetUserId(Long targetUserId) {
        this.targetUserId = targetUserId;
    }

    public String getPermissionType() {
        return permissionType;
    }

    public void setPermissionType(String permissionType) {
        this.permissionType = permissionType;
    }
}