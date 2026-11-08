package com.trustvault.config;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class DatabaseUrlEnvironmentPostProcessorTest {

    @Test
    @DisplayName("Converts postgres:// URI with credentials to standard JDBC properties")
    void testConvertPostgresUriWithCredentials() {
        MockEnvironment env = new MockEnvironment();
        Map<String, Object> target = new HashMap<>();

        String raw = "postgres://neondb_owner:secret123@ep-summer-art.aws.neon.tech/neondb?sslmode=require";
        DatabaseUrlEnvironmentPostProcessor.normalizeUrlAndCredentials(raw, target, env);

        assertEquals("jdbc:postgresql://ep-summer-art.aws.neon.tech:5432/neondb?sslmode=require", target.get("spring.datasource.url"));
        assertEquals("neondb_owner", target.get("spring.datasource.username"));
        assertEquals("secret123", target.get("spring.datasource.password"));
    }

    @Test
    @DisplayName("Converts postgresql:// URI without sslmode to enforce sslmode=require on remote host")
    void testEnforceSslOnRemoteHost() {
        MockEnvironment env = new MockEnvironment();
        Map<String, Object> target = new HashMap<>();

        String raw = "postgresql://myuser:mypass@myhost.render.com:5432/proddb";
        DatabaseUrlEnvironmentPostProcessor.normalizeUrlAndCredentials(raw, target, env);

        assertEquals("jdbc:postgresql://myhost.render.com:5432/proddb?sslmode=require", target.get("spring.datasource.url"));
        assertEquals("myuser", target.get("spring.datasource.username"));
        assertEquals("mypass", target.get("spring.datasource.password"));
    }

    @Test
    @DisplayName("Does not force SSL on localhost")
    void testLocalhostPreservedWithoutSsl() {
        MockEnvironment env = new MockEnvironment();
        Map<String, Object> target = new HashMap<>();

        String raw = "postgres://trustvault_app:pass@localhost:5432/trustvault_db";
        DatabaseUrlEnvironmentPostProcessor.normalizeUrlAndCredentials(raw, target, env);

        assertEquals("jdbc:postgresql://localhost:5432/trustvault_db", target.get("spring.datasource.url"));
        assertEquals("trustvault_app", target.get("spring.datasource.username"));
        assertEquals("pass", target.get("spring.datasource.password"));
    }

    @Test
    @DisplayName("Appends sslmode=require to jdbc:postgresql:// remote URLs if missing")
    void testJdbcPostgresqlAddsSslModeIfMissing() {
        MockEnvironment env = new MockEnvironment();
        Map<String, Object> target = new HashMap<>();

        String raw = "jdbc:postgresql://remote.db.com:5432/mydb";
        DatabaseUrlEnvironmentPostProcessor.normalizeUrlAndCredentials(raw, target, env);

        assertEquals("jdbc:postgresql://remote.db.com:5432/mydb?sslmode=require", target.get("spring.datasource.url"));
    }

    @Test
    @DisplayName("Does not override explicit DB_USERNAME if already custom")
    void testRespectsExplicitCustomUsername() {
        MockEnvironment env = new MockEnvironment();
        env.setProperty("DB_USERNAME", "custom_admin");
        Map<String, Object> target = new HashMap<>();

        String raw = "postgres://urluser:pass@remote.db.com/mydb";
        DatabaseUrlEnvironmentPostProcessor.normalizeUrlAndCredentials(raw, target, env);

        // username in target should NOT be set, preserving custom_admin
        assertNull(target.get("spring.datasource.username"));
        assertEquals("pass", target.get("spring.datasource.password"));
    }
}
