package com.trustvault.config;

import com.trustvault.service.StorageMaintenanceService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.health.contributor.Status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.when;

/**
 * A health endpoint that reports UP no matter what is worse than none, so each
 * branch - never run, stale, failing - is pinned here.
 */
@ExtendWith(MockitoExtension.class)
class StorageMaintenanceHealthIndicatorTest {

    @Mock
    private StorageMaintenanceService maintenanceService;

    private MaintenanceProperties props(boolean enabled, boolean deep, long staleHours) {
        return new MaintenanceProperties(enabled,
                new MaintenanceProperties.Integrity(enabled, "0 0 3 * * *", deep,
                        "0 0 4 * * SUN", staleHours),
                new MaintenanceProperties.Retention(true, "0 30 3 * * *", 30),
                new MaintenanceProperties.Alerts(""));
    }

    @Test
    void disabledMaintenanceDoesNotReportDown() {
        var indicator = new StorageMaintenanceHealthIndicator(maintenanceService,
                props(false, false, 48));

        assertEquals(Status.UP, indicator.health().getStatus());
    }

    @Test
    void neverRunWithinTheWindowIsUpButExplicit() {
        when(maintenanceService.lastIntegrityRun()).thenReturn(Optional.empty());
        when(maintenanceService.monitoringSince()).thenReturn(Instant.now().minus(1, ChronoUnit.HOURS));

        var health = new StorageMaintenanceHealthIndicator(maintenanceService, props(true, false, 48))
                .health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals("not run yet", health.getDetails().get("integritySweep"));
    }

    @Test
    void neverRunBeyondTheWindowIsDown() {
        when(maintenanceService.lastIntegrityRun()).thenReturn(Optional.empty());
        when(maintenanceService.monitoringSince()).thenReturn(Instant.now().minus(96, ChronoUnit.HOURS));

        var health = new StorageMaintenanceHealthIndicator(maintenanceService, props(true, false, 48))
                .health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    void failedSweepIsDown() {
        when(maintenanceService.lastIntegrityRun()).thenReturn(Optional.of(
                new StorageMaintenanceService.Run(Instant.now(), false, "1 missing", "schedule", 3)));

        var health = new StorageMaintenanceHealthIndicator(maintenanceService, props(true, false, 48))
                .health();

        assertEquals(Status.DOWN, health.getStatus());
        assertEquals("1 missing", health.getDetails().get("integritySweep"));
    }

    @Test
    void staleSuccessfulSweepIsDown() {
        when(maintenanceService.lastIntegrityRun()).thenReturn(Optional.of(
                new StorageMaintenanceService.Run(
                        Instant.now().minus(72, ChronoUnit.HOURS), true, "ok", "schedule", 3)));

        var health = new StorageMaintenanceHealthIndicator(maintenanceService, props(true, false, 48))
                .health();

        assertEquals(Status.DOWN, health.getStatus());
    }

    @Test
    void recentSuccessfulSweepIsUpWithItsCounts() {
        when(maintenanceService.lastIntegrityRun()).thenReturn(Optional.of(
                new StorageMaintenanceService.Run(Instant.now(), true, "ok", "schedule", 7)));

        var health = new StorageMaintenanceHealthIndicator(maintenanceService, props(true, false, 48))
                .health();

        assertEquals(Status.UP, health.getStatus());
        assertEquals(7, health.getDetails().get("checkedDocuments"));
    }
}
