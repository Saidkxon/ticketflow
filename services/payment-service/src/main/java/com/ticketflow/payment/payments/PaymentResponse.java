package com.ticketflow.payment.payments;

import java.time.Instant;
import java.util.UUID;

/**
 * A payment attempt as the other services see it (contracts/payment.internal.openapi.yaml, schema Payment). The
 * failure reason is null unless the payment failed. The card and the provider's reference stay inside Payment.
 */
public record PaymentResponse(
        UUID paymentId,
        UUID bookingId,
        int attemptNumber,
        long amountMinor,
        String currency,
        PaymentStatus status,
        String failureReason,
        Instant createdAt,
        Instant updatedAt) {

    static PaymentResponse of(Payment payment) {
        return new PaymentResponse(payment.paymentId(), payment.bookingId(), payment.attemptNumber(),
                payment.amountMinor(), payment.currency(), payment.status(), payment.failureReason(),
                payment.createdAt(), payment.updatedAt());
    }
}
