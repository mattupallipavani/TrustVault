package com.trustvault.exception;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Uniform error body. Deliberately contains no stack trace, exception class
 * name or SQL text: error responses are visible to an attacker.
 */
public class ApiError {

    private final Instant timestamp;
    private final int status;
    private final String error;
    private final String message;
    private final String path;
    private final Map<String, List<String>> fieldErrors;

    public ApiError(Instant timestamp, int status, String error,
                    String message, String path,
                    Map<String, List<String>> fieldErrors) {
        this.timestamp = timestamp;
        this.status = status;
        this.error = error;
        this.message = message;
        this.path = path;
        this.fieldErrors = fieldErrors;
    }

    public Instant getTimestamp() {
        return timestamp;
    }

    public int getStatus() {
        return status;
    }

    public String getError() {
        return error;
    }

    public String getMessage() {
        return message;
    }

    public String getPath() {
        return path;
    }

    public Map<String, List<String>> getFieldErrors() {
        return fieldErrors;
    }
}
