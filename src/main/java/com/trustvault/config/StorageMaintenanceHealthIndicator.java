package com.trustvault.config;

import com.trustvault.service.StorageMaintenanceService;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.HealthIndicator;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;

/**
 * Surfaces the maintenance jobs through {@code /api/health}.
 *
 * This is what turns "a job failed at 03:00" into something a monitor notices:
 * a failed integrity sweep takes the component DOWN, and a sweep that has not
 * run for longer than the configured window does too - because a scheduler that
 * silently stopped is indistinguishable from one that keeps finding nothing
 * wrong, unless someone checks the timestamp.
 */
@Component
public class StorageMaintenanceHealthIndicator implements HealthIndicator {

    private final StorageMaintenanceService maintenanceService;
    private final MaintenanceProperties properties;

    public StorageMaintenanceHealthIndicator(StorageMaintenanceService maintenanceService,
                                             MaintenanceProperties properties) {
        this.maintenanceService = maintenanceService;
        this.properties = properties;
    }

    @Override
    public Health health() {

        if (!properties.enabled() || !properties.integrity().enabled()) {
            return Health.up().withDetail("integritySweep", "disabled").build();
        }

        long staleHours = properties.integrity().staleAfterHours();
        Duration staleAfter = Duration.ofHours(Math.max(1, staleHours));

        var last = maintenanceService.lastIntegrityRun();

        if (last.isEmpty()) {
            Instant since = maintenanceService.monitoringSince();
            if (Instant.now().isAfter(since.plus(staleAfter))) {
                return Health.down()
                        .withDetail("integritySweep", "has not run since startup")
                        .withDetail("monitoringSince", since.toString())
                        .build();
            }
            return Health.up()
                    .withDetail("integritySweep", "not run yet")
                    .withDetail("monitoringSince", since.toString())
                    .build();
        }

        StorageMaintenanceService.Run run = last.get();

        if (!run.healthy()) {
            return Health.down()
                    .withDetail("integritySweep", run.summary())
                    .withDetail("lastRun", run.at().toString())
                    .build();
        }

        if (Instant.now().isAfter(run.at().plus(staleAfter))) {
            return Health.down()
                    .withDetail("integritySweep", "last run is stale")
                    .withDetail("lastRun", run.at().toString())
                    .withDetail("staleAfterHours", staleHours)
                    .build();
        }

        return Health.up()
                .withDetail("integritySweep", "healthy")
                .withDetail("lastRun", run.at().toString())
                .withDetail("checkedDocuments", run.count())
                .build();
    }
}
