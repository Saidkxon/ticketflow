package com.ticketflow.payment.refunds;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * The part of the "refund requested" message that Payment reads (contracts/events/refund-requested.v1.schema.json).
 * Unknown fields are ignored on purpose (ADR-0009, part F).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record RefundRequestedEnvelope(UUID eventId, String eventType, Integer eventVersion, String correlationId, Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(UUID paymentId, UUID bookingId, String reason) {
    }
}
