package com.ticketflow.booking.payments;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * The part of Payment's messages that Booking reads (contracts/events/payment-succeeded.v1.schema.json,
 * payment-failed.v1.schema.json, refund-succeeded.v1.schema.json). Unknown fields are ignored on purpose (ADR-0009,
 * part F).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record PaymentEventEnvelope(UUID eventId, String eventType, Integer eventVersion, String correlationId, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(UUID paymentId, UUID bookingId, Integer attemptNumber, Long amountMinor, String currency,
            String reason) {
    }
}
