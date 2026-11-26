package com.ticketflow.payment.payments;

import java.time.Instant;
import java.util.UUID;

/** A payment attempt as the other services see it (contracts/payment.internal.openapi.yaml, schema Payment). */
public record PaymentResponse(
        UUID paymentId,
        UUID bookingId,
        int attemptNumber,
        long amountMinor,
        String currency,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt) {

    static PaymentResponse of(Payment payment) {
        return new PaymentResponse(payment.paymentId(), payment.bookingId(), payment.attemptNumber(),
                payment.amountMinor(), payment.currency(), payment.status(), payment.createdAt(), payment.updatedAt());
    }
}
