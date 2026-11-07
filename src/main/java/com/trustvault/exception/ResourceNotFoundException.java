package com.trustvault.exception;

/**
 * Thrown when a document, user or other addressed resource does not exist.
 * Mapped to HTTP 404 without leaking internals.
 */
public class ResourceNotFoundException extends RuntimeException {

    public ResourceNotFoundException(String message) {
        super(message);
    }
}
