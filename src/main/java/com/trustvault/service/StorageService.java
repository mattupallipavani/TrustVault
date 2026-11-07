package com.trustvault.service;

import java.io.IOException;
import java.io.InputStream;
import java.time.Duration;

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
     * Stores bytes together with the SHA-256 captured at upload, so integrity
     * can later be checked without re-reading the whole object. Providers that
     * cannot attach metadata fall back to the simple two-argument form and are
     * verified by re-hashing instead.
     */
    default void store(String storageKey, InputStream inputStream, long size,
                       String contentType, String sha256) throws IOException {
        store(storageKey, inputStream, size, contentType);
    }

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

    /**
     * SHA-256 recorded alongside the object at write time, or null when the
     * provider does not keep one. Used to verify a download in O(1) instead of
     * hashing the object on every request.
     */
    default String storedHash(String storageKey) throws IOException {
        return null;
    }

    /**
     * Whether this provider can hand out time-limited direct download URLs.
     */
    default boolean supportsPresignedDownloads() {
        return false;
    }

    /**
     * Issues a short-lived URL that authorizes a single GET. The caller is
     * responsible for having checked permissions first: the URL carries its
     * own authorization and cannot be revoked before it expires.
     */
    default String presignedDownloadUrl(String storageKey, Duration ttl) throws IOException {
        throw new UnsupportedOperationException(
                "Presigned downloads are not supported by this storage provider");
    }
}

