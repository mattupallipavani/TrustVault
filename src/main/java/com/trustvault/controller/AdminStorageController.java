package com.trustvault.controller;

import com.trustvault.config.MaintenanceProperties;
import com.trustvault.service.StorageIntegrityService;
import com.trustvault.service.StorageMaintenanceService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * Administrative storage health. ADMIN only, enforced by the URL rule in
 * SecurityConfig and by the annotation below.
 *
 * Deliberately limited to metadata: this reports whether stored objects still
 * match their recorded hashes. It never returns object contents, so an admin
 * account still cannot read a document through it.
 */
@RestController
@RequestMapping("/api/admin/storage")
@PreAuthorize("hasRole('ADMIN')")
public class AdminStorageController {

    private final StorageMaintenanceService maintenanceService;
    private final MaintenanceProperties maintenanceProperties;

    public AdminStorageController(StorageMaintenanceService maintenanceService,
                                  MaintenanceProperties maintenanceProperties) {
        this.maintenanceService = maintenanceService;
        this.maintenanceProperties = maintenanceProperties;
    }

    /**
     * Verifies every document in the archive.
     *
     * @param deep when true, re-reads every object and recomputes its SHA-256
     *             instead of trusting the hash recorded at upload. Costs one
     *             full read of the archive.
     */
    @PostMapping("/verify")
    public ResponseEntity<StorageIntegrityService.Report> verify(
            @RequestParam(name = "deep", defaultValue = "false") boolean deep) {

        // Routed through the maintenance service, not the verifier directly, so
        // a manual run also updates the health indicator and the alert path -
        // otherwise a human-triggered sweep would not count as "recent".
        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(maintenanceService.runIntegritySweep(deep, "api"));
    }

    /**
     * Runs version retention now, for operators who cannot wait for the cron.
     *
     * @param days override the configured retention age; 0 means "everything
     *             superseded", which is how the policy is tested
     */
    @PostMapping("/retention")
    public ResponseEntity<StorageMaintenanceService.RetentionOutcome> expireVersions(
            @RequestParam(name = "days", required = false) Long days) {

        Duration age = days == null
                ? StorageMaintenanceService.defaultRetention(maintenanceProperties)
                : Duration.ofDays(Math.max(0, days));

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(maintenanceService.runRetention(age, "api"));
    }

    /** When the jobs last ran and whether they succeeded. */
    @GetMapping("/maintenance/status")
    public Map<String, Object> maintenanceStatus() {
        return Map.of(
                "schedulingEnabled", maintenanceProperties.enabled(),
                "retentionDays", maintenanceProperties.retention().daysOrDefault(),
                "deepSweepEnabled", maintenanceProperties.integrity().deep(),
                "lastIntegrityRun", maintenanceService.lastIntegrityRun()
                        .map(List::of).orElse(List.of()),
                "lastRetentionRun", maintenanceService.lastRetentionRun()
                        .map(List::of).orElse(List.of()));
    }

    /** What this deployment can actually do, so callers do not guess. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of(
                "integrityVerification", true,
                "retention", true,
                "scheduled", maintenanceProperties.enabled());
    }
}
