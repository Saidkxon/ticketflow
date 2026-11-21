package com.ticketflow.booking.provisioning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.UUID;

/**
 * The part of the "layout published" message that Booking reads (contracts/events/layout-published.v1.schema.json).
 * Unknown fields are ignored on purpose (ADR-0009, part F).
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record LayoutPublishedEnvelope(UUID eventId, String eventType, Integer eventVersion, String correlationId,
        Data data) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Data(UUID eventId, Long layoutVersion) {
    }
}
