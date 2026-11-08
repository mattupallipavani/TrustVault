package com.trustvault.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;

import java.net.URI;
import java.util.HashMap;
import java.util.Map;

/**
 * Normalizes database connection URLs across cloud hosting platforms (Render, Neon, Supabase, Heroku).
 *
 * <p>Cloud platforms typically provide database URLs in URI format:
 * {@code postgres://user:password@host:port/database} or {@code postgresql://...}.
 * Standard JDBC drivers (PostgreSQL) require:
 * {@code jdbc:postgresql://host:port/database}.
 *
 * <p>This post-processor runs at {@link Ordered#HIGHEST_PRECEDENCE} before DataSource
 * and Flyway initialization, automatically converting URI-style URLs, extracting embedded
 * credentials if needed, ensuring SSL requirement for cloud databases, and mapping
 * {@code DATABASE_URL} to {@code spring.datasource.url}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class DatabaseUrlEnvironmentPostProcessor implements EnvironmentPostProcessor {

    private static final Logger log = LoggerFactory.getLogger(DatabaseUrlEnvironmentPostProcessor.class);
    public static final String PROPERTY_SOURCE_NAME = "trustvaultDatabaseUrlProperties";

    @Override
    public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
        String rawUrl = environment.getProperty("DATABASE_URL");
        if (rawUrl == null || rawUrl.isBlank()) {
            rawUrl = environment.getProperty("DB_URL");
        }
        if (rawUrl == null || rawUrl.isBlank()) {
            rawUrl = environment.getProperty("spring.datasource.url");
        }

        if (rawUrl == null || rawUrl.isBlank()) {
            return;
        }

        Map<String, Object> props = new HashMap<>();
        normalizeUrlAndCredentials(rawUrl, props, environment);

        if (!props.isEmpty()) {
            environment.getPropertySources().addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
            log.info("TrustVault: Normalized database connection properties for target URL host");
        }
    }

    static void normalizeUrlAndCredentials(String rawUrl, Map<String, Object> target, ConfigurableEnvironment env) {
        String trimmed = rawUrl.trim();

        if (trimmed.startsWith("postgres://") || trimmed.startsWith("postgresql://")) {
            try {
                // Convert postgres:// or postgresql:// scheme to URI
                String normalizedSchemeUrl = trimmed.startsWith("postgres://")
                        ? "postgresql://" + trimmed.substring("postgres://".length())
                        : trimmed;

                URI uri = URI.create(normalizedSchemeUrl);
                String userInfo = uri.getUserInfo();
                String host = uri.getHost();
                int port = uri.getPort() == -1 ? 5432 : uri.getPort();
                String path = uri.getPath(); // includes leading '/'
                String query = uri.getQuery();

                if (userInfo != null && !userInfo.isBlank()) {
                    String[] parts = userInfo.split(":", 2);
                    String user = parts[0];
                    String pass = parts.length > 1 ? parts[1] : "";

                    String existingUser = env != null ? env.getProperty("DB_USERNAME") : null;
                    if (existingUser == null || existingUser.isBlank() || "trustvault_app".equals(existingUser)) {
                        target.put("spring.datasource.username", user);
                    }

                    String existingPass = env != null ? env.getProperty("DB_PASSWORD") : null;
                    if (existingPass == null || existingPass.isBlank()) {
                        target.put("spring.datasource.password", pass);
                    }
                }

                StringBuilder jdbcUrl = new StringBuilder("jdbc:postgresql://")
                        .append(host)
                        .append(":")
                        .append(port)
                        .append(path != null ? path : "");

                // Handle query parameters and enforce SSL for non-localhost cloud connections
                boolean hasQuery = query != null && !query.isBlank();
                boolean isLocal = host == null || "localhost".equalsIgnoreCase(host) || "127.0.0.1".equals(host);

                if (hasQuery) {
                    jdbcUrl.append("?").append(query);
                    if (!isLocal && !query.contains("sslmode=")) {
                        jdbcUrl.append("&sslmode=require");
                    }
                } else if (!isLocal) {
                    jdbcUrl.append("?sslmode=require");
                }

                target.put("spring.datasource.url", jdbcUrl.toString());
            } catch (Exception e) {
                log.warn("TrustVault: Failed to parse URI-formatted database URL, using as-is with jdbc: prefix: {}", e.getMessage());
                target.put("spring.datasource.url", "jdbc:" + trimmed);
            }
        } else if (trimmed.startsWith("jdbc:postgresql://")) {
            // Check if remote host lacks sslmode
            if (!trimmed.contains("localhost") && !trimmed.contains("127.0.0.1") && !trimmed.contains("sslmode=")) {
                String separator = trimmed.contains("?") ? "&" : "?";
                target.put("spring.datasource.url", trimmed + separator + "sslmode=require");
            }
        }
    }
}
