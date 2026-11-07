package com.trustvault.config;

import com.trustvault.service.StorageMaintenanceService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * The scheduler only decides *when*; the work, auditing and alerting live in
 * {@link StorageMaintenanceService} so a manual run from the admin API and a
 * scheduled run behave identically.
 *
 * Cron expressions are properties, so a deployment can move these out of
 * business hours without a rebuild.
 */
@Component
public class StorageMaintenanceScheduler {

    private static final Logger log = LoggerFactory.getLogger(StorageMaintenanceScheduler.class);

    private final StorageMaintenanceService maintenanceService;
    private final MaintenanceProperties properties;

    public StorageMaintenanceScheduler(StorageMaintenanceService maintenanceService,
                                       MaintenanceProperties properties) {
        this.maintenanceService = maintenanceService;
        this.properties = properties;
    }

    @Scheduled(cron = "${trustvault.maintenance.integrity.cron:0 0 3 * * *}")
    public void scheduledIntegritySweep() {
        if (!integrityEnabled()) {
            return;
        }
        log.debug("Scheduled storage integrity sweep starting");
        maintenanceService.runIntegritySweep(properties.integrity().deep(), "schedule");
    }

    /**
     * Full re-read of the archive, weekly by default: expensive enough that it
     * does not belong on a daily timer, thorough enough that bit rot cannot
     * hide indefinitely.
     */
    @Scheduled(cron = "${trustvault.maintenance.integrity.deep-cron:0 0 4 * * SUN}")
    public void scheduledDeepIntegritySweep() {
        if (!integrityEnabled() || !properties.integrity().deep()) {
            return;
        }
        log.debug("Scheduled deep storage integrity sweep starting");
        maintenanceService.runIntegritySweep(true, "schedule-deep");
    }

    @Scheduled(cron = "${trustvault.maintenance.retention.cron:0 30 3 * * *}")
    public void scheduledRetention() {
        if (!properties.enabled() || !properties.retention().enabled()) {
            return;
        }
        maintenanceService.runRetention(
                Duration.ofDays(properties.retention().daysOrDefault()), "schedule");
    }

    private boolean integrityEnabled() {
        return properties.enabled() && properties.integrity().enabled();
    }
}
