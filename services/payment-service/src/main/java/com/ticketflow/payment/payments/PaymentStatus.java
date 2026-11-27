package com.ticketflow.payment.payments;

/** Where a payment attempt stands (ADR-0006). Money is tracked here; the state of the booking is Booking's. */
public enum PaymentStatus {
    /** Created, no final answer from the provider yet. A timeout stays here (R5). */
    PENDING,
    SUCCEEDED,
    FAILED,
    /** A refund was requested (ADR-0006 R4, R7) and the provider has not confirmed it yet. */
    REFUND_PENDING,
    /** The money was given back in full. Final. */
    REFUNDED
}
