package com.ticketflow.payment.refunds;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the refund consumer (ticketflow.payment.refunds), the same as Booking's first consumer (ADR-0003 rule 4).
 *
 * @param retryDelay     how long a rejected message waits in the retry queue before it comes back
 * @param maxAttempts    how many tries a message gets before it is parked in the dead-letter queue
 * @param confirmTimeout how long to wait for the broker to confirm that a message was parked
 */
@ConfigurationProperties("ticketflow.payment.refunds")
public record RefundProperties(
        @DefaultValue("PT15S") Duration retryDelay,
        @DefaultValue("8") int maxAttempts,
        @DefaultValue("PT5S") Duration confirmTimeout) {
}
