package com.trustvault.service;

import com.trustvault.util.AuditHasher;
import io.minio.*;
import io.minio.errors.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Enterprise object storage implementation using MinIO / AWS S3 API.
 * Ensures private bucket isolation, UUID key validation, and stream-based hashing.
 */
@Service
@ConditionalOnProperty(name = "trustvault.storage.type", havingValue = "minio")
public class MinioStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(MinioStorageService.class);
    private static final Pattern KEY_PATTERN = Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private final MinioClient minioClient;
    private final String bucketName;

    public MinioStorageService(
            @Value("${trustvault.storage.minio.endpoint:http://localhost:9000}") String endpoint,
            @Value("${trustvault.storage.minio.access-key:minioadmin}") String accessKey,
            @Value("${trustvault.storage.minio.secret-key:minioadmin}") String secretKey,
            @Value("${trustvault.storage.minio.bucket:trustvault-documents}") String bucketName) {

        this.bucketName = bucketName;
        this.minioClient = MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();

        initializeBucket();
    }

    private void initializeBucket() {
        try {
            boolean found = minioClient.bucketExists(BucketExistsArgs.builder().bucket(bucketName).build());
            if (!found) {
                minioClient.makeBucket(MakeBucketArgs.builder().bucket(bucketName).build());
                log.info("Initialized private MinIO bucket: {}", bucketName);
            }
        } catch (Exception e) {
            log.warn("MinIO bucket initialization deferred or connection unavailable: {}", e.getMessage());
        }
    }

    private void validateKey(String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            throw new IllegalArgumentException("Invalid storage key format");
        }
    }

    @Override
    public void store(String storageKey, InputStream inputStream, long size, String contentType) throws IOException {
        validateKey(storageKey);
        try {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(bucketName)
                            .object(storageKey)
                            .stream(inputStream, size, -1)
                            .contentType(contentType)
                            .build()
            );
        } catch (Exception e) {
            throw new IOException("Failed to store object in MinIO", e);
        }
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
            if ("NoSuchKey".equals(e.errorResponse().code())) {
                return false;
            }
            return false;
        } catch (Exception e) {
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
}

