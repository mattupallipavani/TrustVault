package com.trustvault.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Scheduling and alerting policy for storage maintenance.
 *
 * Everything here is off-switchable: a deployment that would rather run these
 * checks from an external scheduler can disable the internal one without
 * losing the endpoints.
 */
@ConfigurationProperties(prefix = "trustvault.maintenance")
public record MaintenanceProperties(

        @DefaultValue("true") boolean enabled,

        @DefaultValue Integrity integrity,

        @DefaultValue Retention retention,

        @DefaultValue Alerts alerts
) {

    /**
     * @param deep         also run a full re-hash sweep on its own schedule
     * @param staleAfterHours the age at which "no recent run" becomes a problem
     */
    public record Integrity(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 0 3 * * *") String cron,
            @DefaultValue("false") boolean deep,
            @DefaultValue("0 0 4 * * SUN") String deepCron,
            @DefaultValue("48") long staleAfterHours
    ) {
    }

    /**
     * @param days age at which a superseded object version is removed
     */
    public record Retention(
            @DefaultValue("true") boolean enabled,
            @DefaultValue("0 30 3 * * *") String cron,
            @DefaultValue("30") long days
    ) {

        public long daysOrDefault() {
            return days <= 0 ? 30 : days;
        }
    }

    /**
     * @param webhookUrl optional Slack-compatible incoming webhook. When empty,
     *                   failures are logged at ERROR and surfaced through the
     *                   health endpoint only.
     */
    public record Alerts(
            @DefaultValue("") String webhookUrl
    ) {
    }
}
