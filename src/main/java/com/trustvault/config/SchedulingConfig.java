package com.trustvault.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A single-thread scheduler is deliberate: these jobs walk the whole archive,
 * so running two at once would only make each slower and the memory peak
 * additive.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties(MaintenanceProperties.class)
public class SchedulingConfig {
}
