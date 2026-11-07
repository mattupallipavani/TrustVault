package com.trustvault.integration;

import com.trustvault.entity.User;
import com.trustvault.enums.Role;
import com.trustvault.repository.AuditLogRepository;
import com.trustvault.repository.UserRepository;
import com.trustvault.service.AuditLogService;
import com.trustvault.util.AuditEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration test that validates audit chain integrity under concurrent writes
 * against a real PostgreSQL database.
 *
 * Uses Testcontainers to spin up a PostgreSQL instance. Skips gracefully if
 * Docker is not available on the build host.
 *
 * The key property we verify: after N concurrent threads each log one audit
 * event, the resulting chain has exactly N new entries, each pointing to the
 * previous one via its hash, and no hashes collide.
 */
@Testcontainers
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
@ActiveProfiles("testcontainers")
class AuditChainConcurrencyIT {

    @Container
    @SuppressWarnings("resource")
    static final PostgreSQLContainer<?> postgres =
            new PostgreSQLContainer<>("postgres:16-alpine")
                    .withDatabaseName("trustvault_test")
                    .withUsername("test")
                    .withPassword("test");

    @DynamicPropertySource
    static void configureProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", postgres::getJdbcUrl);
        registry.add("spring.datasource.username", postgres::getUsername);
        registry.add("spring.datasource.password", postgres::getPassword);
        registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
        registry.add("spring.flyway.enabled", () -> "true");
        registry.add("spring.jpa.hibernate.ddl-auto", () -> "validate");
        registry.add("spring.data.redis.repositories.enabled", () -> "false");
        registry.add("trustvault.storage.location", () -> "target/tc-test-storage");
        registry.add("trustvault.seed.enabled", () -> "false");
        registry.add("trustvault.maintenance.enabled", () -> "false");
    }

    @Autowired
    private AuditLogService auditLogService;

    @Autowired
    private AuditLogRepository auditLogRepository;

    @Autowired
    private UserRepository userRepository;

    private User testUser;

    @BeforeEach
    void setUp() {
        // Create a test user if one doesn't already exist
        testUser = userRepository.findByEmail("audit-test@trustvault.io")
                .orElseGet(() -> {
                    User u = new User();
                    u.setFullName("Audit Test User");
                    u.setEmail("audit-test@trustvault.io");
                    u.setPasswordHash("$2a$12$placeholder.hash.for.test.only");
                    u.setRole(Role.ADMIN);
                    return userRepository.save(u);
                });
    }

    @Test
    @DisplayName("Concurrent audit writes produce a valid hash chain")
    void concurrentAuditWritesProduceValidChain() throws Exception {

        long countBefore = auditLogRepository.count();
        int threadCount = 10;

        ExecutorService executor = Executors.newFixedThreadPool(threadCount);
        CyclicBarrier barrier = new CyclicBarrier(threadCount);
        List<Future<?>> futures = new ArrayList<>();

        for (int i = 0; i < threadCount; i++) {
            final int index = i;
            futures.add(executor.submit(() -> {
                try {
                    // All threads wait here until all are ready, then fire simultaneously
                    barrier.await(5, TimeUnit.SECONDS);

                    auditLogService.success(
                            AuditEvent.by("CONCURRENCY_TEST", testUser)
                                    .details("Thread " + index + " writing audit event"));
                } catch (Exception e) {
                    throw new RuntimeException("Thread " + index + " failed", e);
                }
            }));
        }

        executor.shutdown();
        assertTrue(executor.awaitTermination(30, TimeUnit.SECONDS),
                "Executor did not terminate in time");

        // Check all futures completed without exception
        for (Future<?> future : futures) {
            assertDoesNotThrow(() -> future.get(),
                    "At least one concurrent audit write failed");
        }

        long countAfter = auditLogRepository.count();
        assertEquals(countBefore + threadCount, countAfter,
                "Expected exactly " + threadCount + " new audit entries");

        // Verify the chain is unbroken: each entry's previousHash matches the
        // hash of the entry before it. The chain is ordered by id.
        var allLogs = auditLogRepository.findAll();
        allLogs.sort((a, b) -> Long.compare(a.getId(), b.getId()));

        for (int i = 1; i < allLogs.size(); i++) {
            var current = allLogs.get(i);
            var previous = allLogs.get(i - 1);

            assertEquals(previous.getEntryHash(), current.getPreviousHash(),
                    "Chain broken at entry " + current.getId()
                    + ": previousHash doesn't match predecessor's hash");
        }

        // Verify no duplicate hashes exist
        long distinctHashes = allLogs.stream()
                .map(log -> log.getEntryHash())
                .distinct()
                .count();
        assertEquals(allLogs.size(), distinctHashes,
                "Found duplicate hashes — chain integrity compromised");
    }

    @Test
    @DisplayName("Denied events are also chained correctly")
    void deniedEventsAreChained() {

        long countBefore = auditLogRepository.count();

        auditLogService.denied(
                AuditEvent.by("ACCESS_DENIED_TEST", testUser)
                        .details("Testing denied event chaining"));

        auditLogService.success(
                AuditEvent.by("ACCESS_SUCCESS_TEST", testUser)
                        .details("Following a denied event"));

        long countAfter = auditLogRepository.count();
        assertEquals(countBefore + 2, countAfter);

        var allLogs = auditLogRepository.findAll();
        allLogs.sort((a, b) -> Long.compare(a.getId(), b.getId()));

        // The last two entries should chain properly
        var last = allLogs.get(allLogs.size() - 1);
        var secondLast = allLogs.get(allLogs.size() - 2);

        assertEquals(secondLast.getEntryHash(), last.getPreviousHash(),
                "Denied/success event pair did not chain correctly");
    }
}

