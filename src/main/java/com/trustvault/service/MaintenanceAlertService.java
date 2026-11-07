package com.trustvault.service;

import com.trustvault.config.MaintenanceProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestClient;

import java.util.Map;

/**
 * Delivers maintenance failures somewhere a human will see them.
 *
 * Alerting must never be able to break the thing it is reporting on, so every
 * failure to deliver is swallowed after logging: a broken webhook leaves the
 * audit row and the health endpoint as the remaining record, which is still
 * better than a job that dies mid-sweep.
 */
@Service
public class MaintenanceAlertService {

    private static final Logger log = LoggerFactory.getLogger(MaintenanceAlertService.class);

    private final String webhookUrl;
    private final RestClient restClient;

    public MaintenanceAlertService(MaintenanceProperties properties) {
        this.webhookUrl = properties.alerts().webhookUrl();
        this.restClient = RestClient.builder().build();
    }

    public void alert(String title, String detail) {

        log.error("MAINTENANCE ALERT: {} - {}", title, detail);

        if (webhookUrl == null || webhookUrl.isBlank()) {
            return;
        }

        try {
            restClient.post()
                    .uri(webhookUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(Map.of(
                            "text", "TrustVault maintenance alert: " + title + " - " + detail))
                    .retrieve()
                    .toBodilessEntity();

        } catch (Exception e) {
            // Intentionally not rethrown, and the URL is not logged: a webhook
            // usually embeds its own secret token.
            log.warn("Could not deliver maintenance alert: {}", e.getMessage());
        }
    }
}
