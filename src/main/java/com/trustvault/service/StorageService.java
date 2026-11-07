package com.trustvault.service;

import java.io.IOException;
import java.io.InputStream;

/**
 * Storage provider interface decoupling the domain from physical storage engines.
 * Implementations include LocalStorageService (filesystem) and MinioStorageService (S3/MinIO).
 */
public interface StorageService {

    /**
     * Stores file bytes under an opaque UUID storage key.
     */
    void store(String storageKey, InputStream inputStream, long size, String contentType) throws IOException;

    /**
     * Retrieves the binary content stream for a given storage key.
     */
    InputStream retrieve(String storageKey) throws IOException;

    /**
     * Permanently deletes the binary object.
     */
    void delete(String storageKey) throws IOException;

    /**
     * Checks if the binary object exists in storage.
     */
    boolean exists(String storageKey);

    /**
     * Returns the size in bytes of the stored object.
     */
    long sizeOf(String storageKey) throws IOException;

    /**
     * Calculates the SHA-256 hash of the stored object.
     */
    String hashOf(String storageKey) throws IOException;
}

