package com.ticketflow.payment.payments;

import com.ticketflow.payment.provider.TestCardScenario;
import java.time.Instant;
import java.util.UUID;

/**
 * One payment attempt for one booking.
 *
 * @param scenario          the test card scenario that decides how the simulated provider answers
 * @param cardLast4         the last four digits of the test card, or null; the number itself is never kept
 * @param providerReference the provider's reference once the payment succeeded, otherwise null
 * @param failureReason     why the payment failed, once it failed, otherwise null
 * @param checkCount        how many times the provider was asked about the current step: the charge, then (after a
 *                          refund was requested) the refund
 * @param refundReason      why a refund was requested, once it was, otherwise null
 * @param refundReference   the provider's reference of the refund once it is done, otherwise null
 */
public record Payment(
        UUID paymentId,
        UUID bookingId,
        int attemptNumber,
        long amountMinor,
        String currency,
        PaymentStatus status,
        Instant createdAt,
        Instant updatedAt,
        TestCardScenario scenario,
        String cardLast4,
        String providerReference,
        String failureReason,
        int checkCount,
        String refundReason,
        String refundReference) {
}
