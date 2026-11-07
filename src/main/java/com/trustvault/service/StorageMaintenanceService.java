package com.trustvault.service;

import com.trustvault.config.MaintenanceProperties;
import com.trustvault.util.AuditEvent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Runs the two jobs an archive needs to stay trustworthy: proving the stored
 * bytes still match what was uploaded, and removing superseded object versions
 * so versioning does not become an unbounded second copy of everything.
 *
 * Both used to depend on someone remembering to call an endpoint. Here they are
 * scheduled, recorded in the audit log, and alerted on - and the last result is
 * exposed so a monitor can see it without reading logs.
 */
@Service
public class StorageMaintenanceService {

    private static final Logger log = LoggerFactory.getLogger(StorageMaintenanceService.class);

    private final StorageIntegrityService integrityService;
    private final StorageService storageService;
    private final MaintenanceAlertService alertService;

    private final AtomicReference<Run> lastIntegrity = new AtomicReference<>();
    private final AtomicReference<Run> lastRetention = new AtomicReference<>();

    /** Baseline for "this has never run", so a dead scheduler is detectable. */
    private final Instant monitoringSince = Instant.now();

    public StorageMaintenanceService(StorageIntegrityService integrityService,
                                     StorageService storageService,
                                     MaintenanceAlertService alertService) {
        this.integrityService = integrityService;
        this.storageService = storageService;
        this.alertService = alertService;
    }

    /**
     * Verifies the archive and alerts if anything is wrong.
     * The audit row itself is written by {@link StorageIntegrityService}.
     */
    public StorageIntegrityService.Report runIntegritySweep(boolean deep, String trigger) {

        StorageIntegrityService.Report report = integrityService.verify(deep);
        Run run = new Run(Instant.now(), report.allHealthy(), report.summary(), trigger, report.checked());
        lastIntegrity.set(run);

        if (!report.allHealthy()) {
            alertService.alert("Storage integrity check failed",
                    report.summary() + firstProblem(report));
        }

        return report;
    }

    /**
     * Removes superseded object versions older than {@code age}.
     *
     * A backend without versioning is not a failure: there is simply nothing to
     * expire, and saying so once is more useful than alerting daily.
     */
    public RetentionOutcome runRetention(Duration age, String trigger) {

        try {
            int expired = storageService.expireVersions(age);

            String summary = "Expired " + expired + " superseded object version(s) older than "
                    + age.toDays() + " day(s)";

            lastRetention.set(new Run(Instant.now(), true, summary, trigger, expired));
            log.info(summary);

            return new RetentionOutcome(expired, false, summary);

        } catch (UnsupportedOperationException e) {
            String summary = "Version retention is not supported by this storage backend";
            lastRetention.set(new Run(Instant.now(), true, summary, trigger, 0));
            log.info(summary);
            return new RetentionOutcome(0, true, summary);

        } catch (IOException e) {
            String summary = "Version retention failed: " + e.getMessage();
            lastRetention.set(new Run(Instant.now(), false, summary, trigger, 0));
            alertService.alert("Object version retention failed", summary);
            log.error(summary, e);
            return new RetentionOutcome(0, false, summary);
        }
    }

    public Optional<Run> lastIntegrityRun() {
        return Optional.ofNullable(lastIntegrity.get());
    }

    public Optional<Run> lastRetentionRun() {
        return Optional.ofNullable(lastRetention.get());
    }

    /** When monitoring began, used to judge a sweep that has never run. */
    public Instant monitoringSince() {
        return monitoringSince;
    }

    private String firstProblem(StorageIntegrityService.Report report) {
        return report.issues().isEmpty()
                ? ""
                : "; first: document " + report.issues().get(0).documentId()
                        + " (" + report.issues().get(0).problem() + ")";
    }

    public record Run(Instant at, boolean healthy, String summary, String trigger, int count) {
    }

    public record RetentionOutcome(int expired, boolean unsupported, String message) {
    }

    /**
     * Convenience for the admin endpoint: the configured retention age.
     */
    public static Duration defaultRetention(MaintenanceProperties properties) {
        return Duration.ofDays(properties.retention().daysOrDefault());
    }
}
