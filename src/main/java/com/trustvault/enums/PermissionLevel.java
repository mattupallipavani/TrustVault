package com.trustvault.enums;

/**
 * Permission levels that an owner can grant on a single document.
 *
 * EDIT implies READ: an editor can always read what they can edit. This is
 * enforced in one place, {@code PermissionService}, never in the UI.
 */
public enum PermissionLevel {
    READ,
    EDIT;

    public boolean satisfiesRead() {
        return true;
    }

    public boolean satisfiesEdit() {
        return this == EDIT;
    }

    public static PermissionLevel parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("Permission type is required");
        }
        try {
            return PermissionLevel.valueOf(raw.trim().toUpperCase());
        } catch (IllegalArgumentException exception) {
            throw new IllegalArgumentException("Permission must be READ or EDIT");
        }
    }
}
