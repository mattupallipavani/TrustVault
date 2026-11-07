package com.trustvault.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.regex.Pattern;

/**
 * Enterprise object storage provider for Supabase Free Storage.
 *
 * Interacts directly with the Supabase Storage REST API using Java 21's standard
 * {@link java.net.http.HttpClient}, requiring zero third-party cloud SDKs.
 *
 * Security properties:
 *  - Enforces private bucket access using the backend-only service_role key.
 *  - Storage keys are validated against a strict UUID regex before any path is created.
 *  - The service_role secret key is never sent to the browser or frontend.
 *  - Presigned download URLs are bounded in lifetime to a strict maximum (300 seconds).
 */
@Service
@ConditionalOnProperty(name = "trustvault.storage.type", havingValue = "supabase")
public class SupabaseStorageService implements StorageService {

    private static final Logger log = LoggerFactory.getLogger(SupabaseStorageService.class);
    private static final Pattern KEY_PATTERN =
            Pattern.compile("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private static final long MAX_PRESIGN_SECONDS = 300;
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);

    private final String baseUrl;
    private final String serviceKey;
    private final String bucketName;
    private final HttpClient httpClient;
    private final ObjectMapper objectMapper;

    public SupabaseStorageService(
            @Value("${trustvault.storage.supabase.url:}") String url,
            @Value("${trustvault.storage.supabase.service-key:}") String serviceKey,
            @Value("${trustvault.storage.supabase.bucket:trustvault-documents}") String bucketName) {

        this(url, serviceKey, bucketName, HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build());
    }

    public SupabaseStorageService(String url, String serviceKey, String bucketName, HttpClient httpClient) {
        this.baseUrl = sanitizeUrl(url);
        this.serviceKey = serviceKey != null ? serviceKey.trim() : "";
        this.bucketName = bucketName != null ? bucketName.trim() : "trustvault-documents";
        this.httpClient = httpClient;
        this.objectMapper = new ObjectMapper();

        if (this.baseUrl.isEmpty() || this.serviceKey.isEmpty()) {
            log.warn("Supabase storage configured without complete credentials (url='{}')", this.baseUrl);
        } else {
            log.info("Initialized Supabase Storage Service for bucket '{}' at {}", this.bucketName, this.baseUrl);
        }
    }

    private static String sanitizeUrl(String url) {
        if (url == null || url.isBlank()) {
            return "";
        }
        String trimmed = url.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
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

        byte[] bytes = inputStream.readAllBytes();
        URI uploadUri = URI.create(baseUrl + "/storage/v1/object/" + bucketName + "/" + storageKey);

        HttpRequest request = HttpRequest.newBuilder(uploadUri)
                .header("Authorization", "Bearer " + serviceKey)
                .header("apikey", serviceKey)
                .header("Content-Type", contentType != null ? contentType : "application/octet-stream")
                .header("x-upsert", "false")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofByteArray(bytes))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            if (status != 200 && status != 201) {
                throw new IOException("Supabase storage upload failed with HTTP " + status + ": " + response.body());
            }

            log.debug("Successfully stored object {} in Supabase bucket {}", storageKey, bucketName);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Upload interrupted for storage key: " + storageKey, e);
        }
    }

    @Override
    public InputStream retrieve(String storageKey) throws IOException {
        validateKey(storageKey);

        URI retrieveUri = URI.create(baseUrl + "/storage/v1/object/authenticated/" + bucketName + "/" + storageKey);

        HttpRequest request = HttpRequest.newBuilder(retrieveUri)
                .header("Authorization", "Bearer " + serviceKey)
                .header("apikey", serviceKey)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<InputStream> response = httpClient.send(request, HttpResponse.BodyHandlers.ofInputStream());
            int status = response.statusCode();

            if (status == 404 || status == 400) {
                throw new IOException("Object not found in Supabase storage: " + storageKey);
            }

            if (status != 200) {
                throw new IOException("Supabase storage retrieval failed with HTTP " + status);
            }

            return response.body();

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Retrieval interrupted for storage key: " + storageKey, e);
        }
    }

    @Override
    public void delete(String storageKey) throws IOException {
        validateKey(storageKey);

        URI deleteUri = URI.create(baseUrl + "/storage/v1/object/" + bucketName + "/" + storageKey);

        HttpRequest request = HttpRequest.newBuilder(deleteUri)
                .header("Authorization", "Bearer " + serviceKey)
                .header("apikey", serviceKey)
                .timeout(REQUEST_TIMEOUT)
                .DELETE()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();

            // 200 OK or 404 (already deleted) are both acceptable terminal states
            if (status != 200 && status != 204 && status != 404) {
                throw new IOException("Supabase storage deletion failed with HTTP " + status + ": " + response.body());
            }

            log.debug("Deleted object {} from Supabase bucket {}", storageKey, bucketName);

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Deletion interrupted for storage key: " + storageKey, e);
        }
    }

    @Override
    public boolean exists(String storageKey) {
        if (storageKey == null || !KEY_PATTERN.matcher(storageKey).matches()) {
            return false;
        }

        URI infoUri = URI.create(baseUrl + "/storage/v1/object/info/authenticated/" + bucketName + "/" + storageKey);

        HttpRequest request = HttpRequest.newBuilder(infoUri)
                .header("Authorization", "Bearer " + serviceKey)
                .header("apikey", serviceKey)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            return response.statusCode() == 200;
        } catch (Exception e) {
            log.warn("Supabase unreachable while checking existence for key {}: {}", storageKey, e.getMessage());
            return false;
        }
    }

    @Override
    public long sizeOf(String storageKey) throws IOException {
        validateKey(storageKey);

        URI infoUri = URI.create(baseUrl + "/storage/v1/object/info/authenticated/" + bucketName + "/" + storageKey);

        HttpRequest request = HttpRequest.newBuilder(infoUri)
                .header("Authorization", "Bearer " + serviceKey)
                .header("apikey", serviceKey)
                .timeout(REQUEST_TIMEOUT)
                .GET()
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to get object info from Supabase (HTTP " + response.statusCode() + ")");
            }

            JsonNode root = objectMapper.readTree(response.body());
            JsonNode metadata = root.path("metadata");
            if (metadata.has("size")) {
                return metadata.get("size").asLong();
            }

            if (root.has("size")) {
                return root.get("size").asLong();
            }

            // Fallback: stream byte count
            try (InputStream is = retrieve(storageKey)) {
                return is.readAllBytes().length;
            }

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while querying size for key: " + storageKey, e);
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
                ttl == null ? 60 : ttl.toSeconds(),
                MAX_PRESIGN_SECONDS));

        URI signUri = URI.create(baseUrl + "/storage/v1/object/sign/" + bucketName + "/" + storageKey);
        String requestJson = "{\"expiresIn\":" + seconds + "}";

        HttpRequest request = HttpRequest.newBuilder(signUri)
                .header("Authorization", "Bearer " + serviceKey)
                .header("apikey", serviceKey)
                .header("Content-Type", "application/json")
                .timeout(REQUEST_TIMEOUT)
                .POST(HttpRequest.BodyPublishers.ofString(requestJson))
                .build();

        try {
            HttpResponse<String> response = httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new IOException("Failed to create signed URL in Supabase (HTTP "
                        + response.statusCode() + "): " + response.body());
            }

            JsonNode root = objectMapper.readTree(response.body());
            String signedPath = root.path("signedURL").asText();
            if (signedPath.isBlank()) {
                throw new IOException("Supabase returned empty signedURL in response: " + response.body());
            }

            // If signedPath starts with "/object/sign", append it to baseUrl + "/storage/v1"
            if (signedPath.startsWith("/")) {
                return baseUrl + "/storage/v1" + signedPath;
            }
            return baseUrl + "/storage/v1/" + signedPath;

        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while generating signed URL for key: " + storageKey, e);
        }
    }
}

