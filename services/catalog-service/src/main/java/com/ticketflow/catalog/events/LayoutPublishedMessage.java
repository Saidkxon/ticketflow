package com.ticketflow.catalog.events;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The "layout published" event (ADR-0012, part A; envelope from ADR-0009, part F; schema in
 * contracts/events/layout-published.v1.schema.json). It is small on purpose: it names the event and the layout
 * version, and Booking fetches the immutable layout for that version.
 *
 * <p>The JSON is written by hand because every value is a UUID, a number or a timestamp produced here, never
 * text from a caller, so nothing needs escaping.
 */
final class LayoutPublishedMessage {

    static final String EVENT_TYPE = "LayoutPublished";
    static final int EVENT_VERSION = 1;
    static final String PRODUCER = "catalog-service";

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private LayoutPublishedMessage() {
    }

    /**
     * @param messageId the outbox row ID, sent as the envelope's {@code eventId}
     * @param eventId the ticketed event whose layout was published (the {@code eventId} inside {@code data})
     * @param correlationId the request's correlation ID, or null when there is none
     */
    static String json(UUID messageId, UUID eventId, long layoutVersion, Instant occurredAt, String correlationId) {
        String correlation = correlationId != null && UUID_FORMAT.matcher(correlationId).matches()
                ? "\"" + correlationId + "\""
                : "null";
        return "{\"eventId\":\"" + messageId + "\""
                + ",\"eventType\":\"" + EVENT_TYPE + "\""
                + ",\"eventVersion\":" + EVENT_VERSION
                + ",\"occurredAt\":\"" + DateTimeFormatter.ISO_INSTANT.format(occurredAt.truncatedTo(ChronoUnit.SECONDS)) + "\""
                + ",\"producer\":\"" + PRODUCER + "\""
                + ",\"correlationId\":" + correlation
                + ",\"aggregateId\":\"" + eventId + "\""
                + ",\"data\":{\"eventId\":\"" + eventId + "\",\"layoutVersion\":" + layoutVersion + "}}";
    }
}
