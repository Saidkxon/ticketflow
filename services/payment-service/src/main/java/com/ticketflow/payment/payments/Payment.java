package com.ticketflow.payment.payments;

import java.time.Instant;
import java.util.UUID;

/** One payment attempt for one booking. */
public record Payment(
        UUID paymentId,
        UUID bookingId,
        int attemptNumber,
        long amountMinor,
        String currency,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt) {
}
