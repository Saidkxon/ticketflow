package com.ticketflow.booking.payments;

/**
 * What Booking does with an event of Payment. The handler runs in the same transaction that records the event as
 * processed, so either both happen or neither does, and the broker redelivers the event (ADR-0006 R2, R8).
 *
 * <p>The saga steps are added in the next pull requests: confirmation and failure (PR 23), a payment that arrives too
 * late (PR 24), the end of a refund (PR 25).
 */
interface PaymentEventHandler {

    void handle(PaymentEvent event);
}
