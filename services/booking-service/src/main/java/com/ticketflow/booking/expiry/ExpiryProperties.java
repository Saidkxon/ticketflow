package com.ticketflow.booking.expiry;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the expiry sweeper (ADR-0012, part B).
 *
 * @param scheduleEnabled         whether the sweeper runs by itself every {@code interval}; tests switch it off and
 *                                call it directly
 * @param interval                the wait between two runs. Seats are free again at the deadline whether or not the
 *                                sweeper has run, so this only decides how soon the customer is told
 * @param batchSize               the most bookings one run expires; the rest wait for the next run
 * @param lockTimeout             how long the sweeper waits for a seat that a hold request is changing; after that
 *                                the run ends and the next one tries again
 * @param idempotencyKeyRetention how long the idempotency keys of finished requests are kept (ADR-0009: 24 hours)
 */
@ConfigurationProperties("ticketflow.booking.expiry")
public record ExpiryProperties(
        @DefaultValue("true") boolean scheduleEnabled,
        @DefaultValue("PT10S") Duration interval,
        @DefaultValue("100") int batchSize,
        @DefaultValue("PT2S") Duration lockTimeout,
        @DefaultValue("PT24H") Duration idempotencyKeyRetention) {

    public ExpiryProperties {
        if (batchSize < 1) {
            throw new IllegalArgumentException("ticketflow.booking.expiry.batch-size must be at least 1");
        }
        if (lockTimeout.isZero() || lockTimeout.isNegative()) {
            throw new IllegalArgumentException("ticketflow.booking.expiry.lock-timeout must be positive");
        }
        if (idempotencyKeyRetention.isZero() || idempotencyKeyRetention.isNegative()) {
            throw new IllegalArgumentException("ticketflow.booking.expiry.idempotency-key-retention must be positive");
        }
    }
}
