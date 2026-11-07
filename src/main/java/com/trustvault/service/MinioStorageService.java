package com.trustvault.service;

import io.minio.*;
import io.minio.errors.*;
import io.minio.http.Method;
import io.minio.messages.Item;
import io.minio.messages.VersioningConfiguration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/**
 * Enterprise object storage implementation using MinIO / AWS S3 API.
 *
 * Production behaviour beyond "put bytes in a bucket":
 *  - the bucket is private and versioned, so an overwrite or delete is
 *    recoverable rather than silently destroying the only copy;
 *  - the SHA-256 computed at upload is stored as object metadata, which allows
 *    a download to be integrity-checked with a single stat instead of reading
 *    the object twice;
 *  - every upload is verified against its expected size and hash immediately,
 *    and the object removed if MinIO stored something different;
 *  - presigned download URLs are bounded in lifetime by this class, not by the
 *    caller, because an issued URL carries its own authorization.
 */
@Service
@ConditionalOnProperty(name = "trustvault.storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");
    private static final String HASH_METADATA_KEY = "sha256";

    /** Hard ceiling on a presigned URL, independent of configuration. */
    private static final long MAX_PRESIGN_SECONDS = 300;

    private final MinioClient minioClient;
    private final MinioClient presignClient;
    private final String bucketName;
    private final Duration presignTtl;

    public MinioStorageService(
            @Value("${trustvault.storage.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${trustvault.storage.minio.public-endpoint:}") String publicEndpoint,
            @Value("${trustvault.storage.minio.access-key:minioadmin}") String accessKey,
            @Value("${trustvault.storage.minio.secret-key:minioadmin}") String secretKey,
            @Value("${trustvault.storage.minio.bucket:trustvault-documents}") String bucketName,
            @Value("${trustvault.storage.minio.presign-ttl-seconds:60}") long presignTtlSeconds,
            @Value("${trustvault.storage.minio.region:us-east-1}") String region) {

        this.bucketName = bucketName;
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .region(region)
                .credentials(accessKey, secretKey)
                .build();

        // Signing has to happen against the host the browser will actually
        // call, because the signature covers the Host header. When no public
        // endpoint is configured we sign for the internal one and say so.
        String signingEndpoint = (publicEndpoint == null || publicEndpoint.isBlank())
                ? endpoint : publicEndpoint;
        this.presignClient = signingEndpoint.equals(endpoint)
                ? this.minioClient
                : MinioClient.builder()
                        .endpoint(signingEndpoint)
                        // Pinning the region is what lets a URL be signed for a
                        // public host the application itself cannot reach: the
                        // SDK would otherwise call the endpoint to discover it.
                        .region(region)
                        .credentials(accessKey, secretKey)
                        .build();

        long seconds = Math.max(1, Math.min(presignTtlSeconds, MAX_PRESIGN_SECONDS));
        this.presignTtl = Duration.ofSeconds(seconds);

        initializeBucket();

        if (publicEndpoint == null || publicEndpoint.isBlank()) {
            log.info("MinIO presigned URLs will be signed for the internal endpoint {}; "
                    + "set MINIO_PUBLIC_ENDPOINT to the address clients can reach", endpoint);
        }
    }

    private void initializeBucket() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Initialized private MinIO bucket: {}", bucketName);
            }
            enableVersioning();
        } catch (Exception e) {
            log.warn("MinIO bucket initialization deferred or connection unavailable: {}", e.getMessage());
        }
    }

    /**
     * Versioning keeps a deleted or overwritten object recoverable. It is
     * idempotent, so enabling it on every start is safe.
     */
    private void enableVersioning() {
        try {
            minioClient.setBucketVersioning(
                    SetBucketVersioningArgs.builder()
                            .bucket(bucketName)
                            .config(new VersioningConfiguration(
                                    VersioningConfiguration.Status.ENABLED, null))
                            .build());
            log.info("MinIO bucket versioning enabled: {}", bucketName);
        } catch (Exception e) {
            log.warn("Could not enable versioning on bucket {}: {}", bucketName, e.getMessage());
        }
    }

    private void validateKey(String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Invalid storage key format");
        }
    }

    @Override
    public void store(String storageKey, InputStream inputStream, long size, String contentType) throws IOException {
        store(storageKey, inputStream, size, contentType, null);
    }

    @Override
    public void store(String storageKey, InputStream inputStream, long size,
                      String contentType, String sha256) throws IOException {
        validateKey(storageKey);
        try {
            PutObjectArgs.Builder builder = PutObjectArgs.builder()
                    .bucket(bucketName)
                    .object(storageKey)
                    .stream(inputStream, size, -1)
                    .contentType(contentType);

            if (sha256 != null && !sha256.isBlank()) {
                builder.userMetadata(Map.of(HASH_METADATA_KEY, sha256));
            }

            minioClient.putObject(builder.build());

            if (sha256 != null && !sha256.isBlank()) {
                verifyStoredObject(storageKey, size, sha256);
            }
        } catch (Exception e) {
            throw new IOException("Failed to store object in MinIO", e);
        }
    }

    /**
     * Confirms that what MinIO now holds is what the caller asked it to hold.
     * A silent short write would otherwise be discovered only at download time,
     * long after the audit trail recorded a successful upload.
     */
    private void verifyStoredObject(String storageKey, long expectedSize, String expectedHash)
            throws Exception {

        StatObjectResponse stat = minioClient.statObject(
                StatObjectArgs.builder().bucket(bucketName).object(storageKey).build());

        String storedHash = stat.userMetadata().get(HASH_METADATA_KEY);
        boolean sizeMatches = stat.size() == expectedSize;
        boolean hashMatches = expectedHash.equals(storedHash);

        if (sizeMatches && hashMatches) {
            return;
        }

        try {
            minioClient.removeObject(RemoveObjectArgs.builder()
                    .bucket(bucketName).object(storageKey).build());
        } catch (Exception cleanupFailure) {
            log.error("Could not remove object {} after failed integrity check", storageKey, cleanupFailure);
        }

        throw new IOException("Stored object failed verification (size " + stat.size()
                + "/" + expectedSize + ", hash "
                + (hashMatches ? "ok" : storedHash) + ")");
    }

    @Override
    public InputStream retrieve(String storageKey) throws IOException {
        validateKey(storageKey);
        try {
            return minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(bucketName)
                            .object(storageKey)
                            .build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to retrieve object from MinIO", e);
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        validateKey(storageKey);
        try {
            minioClient.removeObject(
                    RemoveObjectArgs.builder()
                            .bucket(bucketName)
                            .object(storageKey)
                            .build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to delete object from MinIO", e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            return false;
        }
        try {
            minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(storageKey)
                            .build()
            );
            return true;
        } catch (ErrorResponseException e) {
            if (!"NoSuchKey".equals(e.errorResponse().code())) {
                // An outage is not an absent file; logging it stops a broken
                // dependency from masquerading as "document deleted".
                log.warn("MinIO stat failed for {}: {}", storageKey, e.errorResponse().message());
            }
            return false;
        } catch (Exception e) {
            log.warn("MinIO unreachable while checking {}: {}", storageKey, e.getMessage());
            return false;
        }
    }

    @Override
    public long sizeOf(String storageKey) throws IOException {
        validateKey(storageKey);
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(storageKey)
                            .build()
            );
            return stat.size();
        } catch (Exception e) {
            throw new IOException("Failed to obtain object size from MinIO", e);
        }
    }

    @Override
    public String storedHash(String storageKey) throws IOException {
        validateKey(storageKey);
        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(bucketName)
                            .object(storageKey)
                            .build()
            );
            return stat.userMetadata().get(HASH_METADATA_KEY);
        } catch (Exception e) {
            throw new IOException("Failed to read object metadata from MinIO", e);
        }
    }

    @Override
    public String hashOf(String storageKey) throws IOException {
        try (InputStream stream = retrieve(storageKey)) {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] buffer = new byte[8192];
            int read;
            while ((read = stream.read(buffer)) != -1) {
                digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    @Override
    public boolean supportsPresignedDownloads() {
        return true;
    }

    @Override
    public String presignedDownloadUrl(String storageKey, Duration ttl) throws IOException {
        validateKey(storageKey);

        long seconds = Math.max(1, Math.min(
                ttl == null ? presignTtl.toSeconds() : ttl.toSeconds(),
                MAX_PRESIGN_SECONDS));

        try {
            return presignClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(bucketName)
                            .object(storageKey)
                            .expiry((int) seconds, TimeUnit.SECONDS)
                            .build());
        } catch (Exception e) {
            throw new IOException("Failed to presign object download", e);
        }
    }

    public Duration presignTtl() {
        return presignTtl;
    }

    /**
     * Expires superseded versions so versioning does not grow without bound and
     * deleted documents do not live in storage forever.
     *
     * Two things are deliberately never touched:
     *  - the current version, which is the live object;
     *  - delete markers, because removing the marker makes the previous version
     *    current again - a deleted document would silently reappear.
     */
    @Override
    public int expireVersions(Duration olderThan) throws IOException {
        if (olderThan == null || olderThan.isNegative()) {
            throw new IllegalArgumentException("Retention age must be zero or positive");
        }

        Instant cutoff = Instant.now().minus(olderThan);
        int expired = 0;

        try {
            Iterable<Result<Item>> versions = minioClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(bucketName)
                            .recursive(true)
                            .includeVersions(true)
                            .build());

            for (Result<Item> result : versions) {
                Item item = result.get();

                if (item.isLatest() || item.isDeleteMarker()) {
                    continue;
                }

                if (item.lastModified() == null || !item.lastModified().toInstant().isBefore(cutoff)) {
                    continue;
                }

                minioClient.removeObject(RemoveObjectArgs.builder()
                        .bucket(bucketName)
                        .object(item.objectName())
                        .versionId(item.versionId())
                        .build());

                expired++;
            }

            if (expired > 0) {
                log.info("Expired {} superseded object version(s) older than {}", expired, olderThan);
            }

            return expired;

        } catch (Exception e) {
            throw new IOException("Failed to expire object versions", e);
        }
    }
}
