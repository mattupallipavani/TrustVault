package com.trustvault.service;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.IOException;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * The point of these jobs is that nobody is watching when they run, so the
 * behaviour that matters is what happens on failure: it must be recorded and
 * escalated, not swallowed.
 */
@ExtendWith(MockitoExtension.class)
class StorageMaintenanceServiceTest {

    @Mock
    private StorageIntegrityService integrityService;

    @Mock
    private StorageService storageService;

    @Mock
    private MaintenanceAlertService alertService;

    private StorageMaintenanceService service;

    @BeforeEach
    void setUp() {
        service = new StorageMaintenanceService(integrityService, storageService, alertService);
    }

    private StorageIntegrityService.Report report(boolean healthy) {
        List<StorageIntegrityService.Issue> issues = healthy
                ? List.of()
                : List.of(new StorageIntegrityService.Issue(9L, "lost.pdf", "Object is missing from storage"));
        return new StorageIntegrityService.Report(3, healthy ? 3 : 2, healthy ? 0 : 1, 0, 0,
                false, healthy, healthy ? "all good" : "1 missing", issues);
    }

    @Test
    void alertsWhenTheSweepFindsAProblem() {
        when(integrityService.verify(false)).thenReturn(report(false));

        var report = service.runIntegritySweep(false, "schedule");

        assertFalse(report.allHealthy());
        // The alert carries the document id and the problem, not the file name:
        // alerts travel to chat tools and should not leak document titles.
        var detail = org.mockito.ArgumentCaptor.forClass(String.class);
        verify(alertService).alert(anyString(), detail.capture());
        assertTrue(detail.getValue().contains("document 9"));
        assertFalse(detail.getValue().contains("lost.pdf"));
        assertTrue(service.lastIntegrityRun().isPresent());
    }

    @Test
    void staysQuietWhenTheArchiveIsHealthy() {
        when(integrityService.verify(true)).thenReturn(report(true));

        var report = service.runIntegritySweep(true, "schedule-deep");

        assertTrue(report.allHealthy());
        verify(alertService, never()).alert(anyString(), anyString());
    }

    @Test
    void retentionReportsHowManyVersionsItRemoved() throws IOException {
        when(storageService.expireVersions(Duration.ofDays(30))).thenReturn(4);

        StorageMaintenanceService.RetentionOutcome outcome =
                service.runRetention(Duration.ofDays(30), "schedule");

        assertEquals(4, outcome.expired());
        assertFalse(outcome.unsupported());
        verify(alertService, never()).alert(anyString(), anyString());
    }

    @Test
    void unsupportedRetentionIsNotTreatedAsFailure() throws IOException {
        when(storageService.expireVersions(any())).thenThrow(new UnsupportedOperationException("no versions"));

        StorageMaintenanceService.RetentionOutcome outcome =
                service.runRetention(Duration.ofDays(30), "schedule");

        assertTrue(outcome.unsupported());
        assertEquals(0, outcome.expired());
        verify(alertService, never()).alert(anyString(), anyString());
    }

    @Test
    void retentionFailureIsAlerted() throws IOException {
        when(storageService.expireVersions(any())).thenThrow(new IOException("MinIO unreachable"));

        StorageMaintenanceService.RetentionOutcome outcome =
                service.runRetention(Duration.ofDays(30), "schedule");

        assertEquals(0, outcome.expired());
        verify(alertService).alert(anyString(), contains("MinIO unreachable"));
        assertFalse(service.lastRetentionRun().orElseThrow().healthy());
    }

    @Test
    void sweepPassesTheRequestedDepthToTheVerifier() {
        when(integrityService.verify(anyBoolean())).thenReturn(report(true));

        service.runIntegritySweep(true, "api");
        service.runIntegritySweep(false, "api");

        verify(integrityService).verify(true);
        verify(integrityService).verify(false);
    }
}
