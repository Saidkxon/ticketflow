package com.ticketflow.payment.payments;

/** Where a payment attempt stands (ADR-0006). Money is tracked here; the state of the booking is Booking's. */
public enum PaymentStatus {
    /** Created, no final answer from the provider yet. A timeout stays here (R5). */
    PENDING,
    SUCCEEDED,
    FAILED
}
