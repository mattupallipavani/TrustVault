package com.trustvault.util;

import com.trustvault.entity.AuditLog;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;

/**
 * Canonical serialisation and hashing for audit entries.
 *
 * Both the writer and the verifier use this class, which is the only reason a
 * chain check is meaningful: if the two sides built the string differently the
 * chain would never verify. Any new field added to {@link AuditLog} and
 * covered by the hash must be added here in a fixed position, and old rows
 * would then fail verification - which is the honest behaviour for a record
 * format change.
 *
 * Fields separated by U+001F (unit separator) rather than '|' so that a
 * document name containing a pipe cannot shift the field boundaries.
 */
public final class AuditHasher {

    public static final String GENESIS = "GENESIS";

    private static final String SEPARATOR = "\u001F";

    private AuditHasher() {
    }

    public static String canonical(AuditLog log) {
        return join(
                value(log.getActorUserId()),
                value(log.getActorEmail()),
                value(log.getDocumentId()),
                value(log.getDocumentName()),
                value(log.getTargetUserId()),
                value(log.getTargetEmail()),
                value(log.getAction()),
                value(log.getOutcome()),
                value(log.getDetails()),
                value(log.getIpAddress()),
                value(log.getUserAgent()),
                value(log.getCreatedAt()),
                value(log.getPreviousHash())
        );
    }

    public static String entryHash(AuditLog log) {
        return sha256(canonical(log));
    }

    public static String sha256(String input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(input.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    public static String sha256(byte[] input) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(input));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static String join(String... parts) {
        StringBuilder builder = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) {
                builder.append(SEPARATOR);
            }
            builder.append(parts[i]);
        }
        return builder.toString();
    }

    /**
     * Explicit null marker so that "null" as a literal value cannot collide
     * with a missing field.
     */
    private static String value(Object input) {
        return input == null ? "\u0000NULL" : input.toString();
    }
}
