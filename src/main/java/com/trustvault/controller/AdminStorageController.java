package com.trustvault.controller;

import com.trustvault.service.StorageIntegrityService;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.*;

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

    private final StorageIntegrityService integrityService;

    public AdminStorageController(StorageIntegrityService integrityService) {
        this.integrityService = integrityService;
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

        return ResponseEntity.ok()
                .header("Cache-Control", "no-store")
                .body(integrityService.verify(deep));
    }

    /** What this deployment can actually do, so callers do not guess. */
    @GetMapping("/capabilities")
    public Map<String, Object> capabilities() {
        return Map.of("integrityVerification", true);
    }
}
