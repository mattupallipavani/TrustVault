package com.trustvault.dto;

import java.time.Instant;

/**
 * Result of a chain verification, shaped for the admin screen.
 */
public class AuditVerificationResponse {

    private final boolean valid;
    private final int checkedEntries;
    private final Long brokenAtId;
    private final String message;
    private final int idGaps;
    private final Instant checkedAt;

    private AuditVerificationResponse(boolean valid, int checkedEntries,
                                      Long brokenAtId, String message,
                                      int idGaps) {
        this.valid = valid;
        this.checkedEntries = checkedEntries;
        this.brokenAtId = brokenAtId;
        this.message = message;
        this.idGaps = idGaps;
        this.checkedAt = Instant.now();
    }

    public static AuditVerificationResponse valid(int checkedEntries, int idGaps,
                                                  String headHash) {
        return new AuditVerificationResponse(
                true,
                checkedEntries,
                null,
                "Audit chain verified. Chain head " + headHash + ".",
                idGaps
        );
    }

    public static AuditVerificationResponse broken(int checkedEntries,
                                                   Long brokenAtId,
                                                   String message) {
        return new AuditVerificationResponse(
                false, checkedEntries, brokenAtId, message, 0);
    }

    public boolean isValid() {
        return valid;
    }

    public int getCheckedEntries() {
        return checkedEntries;
    }

    public Long getBrokenAtId() {
        return brokenAtId;
    }

    public String getMessage() {
        return message;
    }

    public int getIdGaps() {
        return idGaps;
    }

    public Instant getCheckedAt() {
        return checkedAt;
    }
}
