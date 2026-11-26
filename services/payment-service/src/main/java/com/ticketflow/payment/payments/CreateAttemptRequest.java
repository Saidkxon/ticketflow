package com.ticketflow.payment.payments;

import java.util.UUID;

/**
 * Body of POST /internal/v1/payment-attempts (contracts/payment.internal.openapi.yaml). Fields are nullable so a missing
 * one is reported as a validation problem. The booking ID is repeated in the idempotency key and the two must agree.
 */
public record CreateAttemptRequest(UUID bookingId, Long amountMinor, String currency) {
}
