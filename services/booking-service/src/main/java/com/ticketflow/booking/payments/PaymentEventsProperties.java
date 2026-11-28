package com.ticketflow.booking.payments;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the consumer of Payment's events (ticketflow.booking.payment-events), the same as the first consumer.
 *
 * @param maxAttempts    how many times a message is tried before it is parked in the dead-letter queue
 * @param retryDelay     how long a failed message waits before the next attempt (a time-to-live on the retry queue)
 * @param confirmTimeout how long to wait for the broker to confirm that a message was parked
 */
@ConfigurationProperties("ticketflow.booking.payment-events")
public record PaymentEventsProperties(
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("15s") Duration retryDelay,
        @DefaultValue("5s") Duration confirmTimeout) {
}
