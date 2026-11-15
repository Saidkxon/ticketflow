package com.ticketflow.gateway.limits;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.util.unit.DataSize;

/**
 * Request limits (ADR-0013, rules G3 and G7). The numbers are starting points to tune from measurements.
 *
 * @param authPerMinute    login, registration, refresh and logout, per client address
 * @param defaultPerMinute every other route, per client address
 * @param maxBodySize      largest request body the gateway accepts
 */
@ConfigurationProperties("ticketflow.gateway.limits")
public record LimitsProperties(
        @DefaultValue("10") int authPerMinute,
        @DefaultValue("100") int defaultPerMinute,
        @DefaultValue("16KB") DataSize maxBodySize) {
}
