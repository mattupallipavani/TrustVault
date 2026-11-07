package com.trustvault.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class SupabaseStorageServiceTest {

    private static final String SUPABASE_URL = "https://test-project.supabase.co";
    private static final String SERVICE_KEY = "test-service-role-key";
    private static final String BUCKET = "test-bucket";

    @Mock
    private HttpClient httpClient;

    @Mock
    private HttpResponse<String> stringResponse;

    @Mock
    private HttpResponse<InputStream> streamResponse;

    private SupabaseStorageService storageService;

    @BeforeEach
    void setUp() {
        storageService = new SupabaseStorageService(
                SUPABASE_URL, SERVICE_KEY, BUCKET, httpClient);
    }

    @Test
    @DisplayName("Successfully stores object via Supabase REST API")
    void storesObjectSuccessfully() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] content = "Hello Supabase Storage".getBytes(StandardCharsets.UTF_8);

        when(stringResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stringResponse);

        assertDoesNotThrow(() -> storageService.store(
                key, new ByteArrayInputStream(content), content.length, "text/plain"));

        verify(httpClient).send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class));
    }

    @Test
    @DisplayName("Rejects malformed storage key format")
    void rejectsMalformedStorageKey() {
        assertThrows(IllegalArgumentException.class, () ->
                storageService.retrieve("not-a-valid-uuid-key"));
    }

    @Test
    @DisplayName("Retrieves object binary stream successfully")
    void retrievesObjectSuccessfully() throws Exception {
        String key = UUID.randomUUID().toString();
        byte[] content = "Test document content".getBytes(StandardCharsets.UTF_8);

        when(streamResponse.statusCode()).thenReturn(200);
        when(streamResponse.body()).thenReturn(new ByteArrayInputStream(content));
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(streamResponse);

        try (InputStream stream = storageService.retrieve(key)) {
            assertNotNull(stream);
            byte[] read = stream.readAllBytes();
            assertArrayEquals(content, read);
        }
    }

    @Test
    @DisplayName("Deletes object successfully")
    void deletesObjectSuccessfully() throws Exception {
        String key = UUID.randomUUID().toString();

        when(stringResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stringResponse);

        assertDoesNotThrow(() -> storageService.delete(key));
    }

    @Test
    @DisplayName("Checks object existence")
    void checksObjectExistence() throws Exception {
        String key = UUID.randomUUID().toString();

        when(stringResponse.statusCode()).thenReturn(200);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stringResponse);

        assertTrue(storageService.exists(key));
    }

    @Test
    @DisplayName("Generates presigned download URL")
    void generatesPresignedDownloadUrl() throws Exception {
        String key = UUID.randomUUID().toString();
        String json = "{\"signedURL\":\"/object/sign/test-bucket/" + key + "?token=xyz\"}";

        when(stringResponse.statusCode()).thenReturn(200);
        when(stringResponse.body()).thenReturn(json);
        when(httpClient.send(any(HttpRequest.class), any(HttpResponse.BodyHandler.class)))
                .thenReturn(stringResponse);

        String url = storageService.presignedDownloadUrl(key, Duration.ofSeconds(60));
        assertNotNull(url);
        assertTrue(url.contains(SUPABASE_URL + "/storage/v1/object/sign/test-bucket/" + key));
    }
}
