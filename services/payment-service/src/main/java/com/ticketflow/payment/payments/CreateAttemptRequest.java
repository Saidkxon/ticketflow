package com.ticketflow.payment.payments;

import java.util.UUID;

/**
 * Body of POST /internal/v1/payment-attempts (contracts/payment.internal.openapi.yaml). Fields are nullable so a missing
 * one is reported as a validation problem. The booking ID is repeated in the idempotency key and the two must agree.
 *
 * <p>{@code testCard} is a test card number for the simulated provider; it decides how the payment ends. Payment keeps
 * the scenario and the last four digits only. A real provider would take a token here, never a card number.
 */
public record CreateAttemptRequest(UUID bookingId, Long amountMinor, String currency, String testCard) {

    /** The card number must not end up in a log through this object. */
    @Override
    public String toString() {
        return "CreateAttemptRequest[bookingId=" + bookingId + ", amountMinor=" + amountMinor
                + ", currency=" + currency + ", testCard=" + (testCard == null ? "none" : "hidden") + "]";
    }
}
