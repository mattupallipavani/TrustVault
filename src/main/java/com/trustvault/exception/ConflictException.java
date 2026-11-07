package com.trustvault.exception;

/**
 * Thrown when a request is well formed but conflicts with current state,
 * for example registering an email that already exists.
 */
public class ConflictException extends RuntimeException {

    public ConflictException(String message) {
        super(message);
    }
}
