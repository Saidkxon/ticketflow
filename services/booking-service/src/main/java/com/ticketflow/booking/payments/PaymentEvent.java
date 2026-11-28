package com.ticketflow.booking.payments;

import java.util.UUID;

/**
 * A checked event of Payment, as the handlers see it. {@code reason} is set for a failed payment and for a refund, and
 * is null for a success.
 */
record PaymentEvent(UUID eventId, PaymentEventKind kind, UUID paymentId, UUID bookingId, int attemptNumber,
        long amountMinor, String currency, String reason) {
}
