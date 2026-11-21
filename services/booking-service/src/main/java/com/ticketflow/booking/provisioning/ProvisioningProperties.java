package com.ticketflow.booking.provisioning;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How Booking builds the seat inventory of a newly published event (ADR-0012, part A; ADR-0003).
 *
 * @param catalogUri     where Booking fetches the layout snapshot (inside the container network)
 * @param maxAttempts    how many times a message is tried before it is parked in the dead-letter queue
 * @param retryDelay     how long a failed message waits before the next attempt (a time-to-live on the retry queue)
 * @param confirmTimeout how long to wait for the broker to confirm that a message was parked
 */
@ConfigurationProperties("ticketflow.booking.provisioning")
public record ProvisioningProperties(
        @DefaultValue("http://localhost:18081") String catalogUri,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("15s") Duration retryDelay,
        @DefaultValue("5s") Duration confirmTimeout) {
}
