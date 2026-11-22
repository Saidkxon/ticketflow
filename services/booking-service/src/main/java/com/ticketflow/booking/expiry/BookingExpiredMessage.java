package com.ticketflow.booking.expiry;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The "booking expired" event (ADR-0006; envelope from ADR-0009, part F; schema in
 * contracts/events/booking-expired.v1.schema.json). It carries identifiers and one timestamp, no personal data:
 * Notification looks up what it needs about the customer itself.
 *
 * <p>The JSON is written by hand because every value is a UUID or a timestamp produced here, never text from a
 * caller, so nothing needs escaping.
 */
final class BookingExpiredMessage {

    static final String EVENT_TYPE = "BookingExpired";
    static final int EVENT_VERSION = 1;
    static final String PRODUCER = "booking-service";

    private static final Pattern UUID_FORMAT = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$");

    private BookingExpiredMessage() {
    }

    /**
     * @param messageId     the outbox row ID, sent as the envelope's {@code eventId}
     * @param occurredAt    when the sweeper expired the booking
     * @param expiredAt     the hold deadline that passed
     * @param correlationId the correlation ID of the request that caused the message, or null: the sweeper is not a request
     */
    static String json(UUID messageId, UUID bookingId, UUID eventId, UUID userId, Instant occurredAt, Instant expiredAt,
            String correlationId) {
        String correlation = correlationId != null && UUID_FORMAT.matcher(correlationId).matches()
                ? "\"" + correlationId + "\""
                : "null";
        return "{\"eventId\":\"" + messageId + "\""
                + ",\"eventType\":\"" + EVENT_TYPE + "\""
                + ",\"eventVersion\":" + EVENT_VERSION
                + ",\"occurredAt\":\"" + seconds(occurredAt) + "\""
                + ",\"producer\":\"" + PRODUCER + "\""
                + ",\"correlationId\":" + correlation
                + ",\"aggregateId\":\"" + bookingId + "\""
                + ",\"data\":{\"bookingId\":\"" + bookingId + "\",\"eventId\":\"" + eventId + "\""
                + ",\"userId\":\"" + userId + "\",\"expiredAt\":\"" + seconds(expiredAt) + "\"}}";
    }

    private static String seconds(Instant time) {
        return DateTimeFormatter.ISO_INSTANT.format(time.truncatedTo(ChronoUnit.SECONDS));
    }
}
