package com.trustvault.config;

import com.trustvault.service.StorageService;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

@Component
public class StorageHealthIndicator implements HealthIndicator {

    private final StorageService storageService;

    public StorageHealthIndicator(StorageService storageService) {
        this.storageService = storageService;
    }

    @Override
    public Health health() {
        try {
            // Check existence probe for a non-existent key to test engine responsiveness
            storageService.exists("00000000-0000-0000-0000-000000000000");
            return Health.up()
                    .withDetail("storageType", storageService.getClass().getSimpleName())
                    .build();
        } catch (Exception e) {
            return Health.down(e)
                    .withDetail("storageType", storageService.getClass().getSimpleName())
                    .build();
        }
    }
}

