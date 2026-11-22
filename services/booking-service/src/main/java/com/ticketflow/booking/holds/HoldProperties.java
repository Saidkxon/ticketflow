package com.ticketflow.booking.holds;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param maxSeats    the most seats one hold may contain (ADR-0012: default 8)
 * @param duration    how long a hold lasts, counted from the moment the seats are taken (ADR-0012: 10 minutes)
 * @param lockTimeout how long a hold request waits for a seat that another request is changing at this moment; after
 *                    that it answers "try again" instead of letting a queue grow (ADR-0012)
 */
@ConfigurationProperties("ticketflow.booking.holds")
public record HoldProperties(
        @DefaultValue("8") int maxSeats,
        @DefaultValue("10m") Duration duration,
        @DefaultValue("2s") Duration lockTimeout) {

    public HoldProperties {
        if (maxSeats < 1) {
            throw new IllegalArgumentException("ticketflow.booking.holds.max-seats must be at least 1");
        }
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("ticketflow.booking.holds.duration must be positive");
        }
        if (lockTimeout.isZero() || lockTimeout.isNegative()) {
            throw new IllegalArgumentException("ticketflow.booking.holds.lock-timeout must be positive");
        }
    }
}
