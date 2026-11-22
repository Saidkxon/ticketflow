package com.ticketflow.booking.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The message follows contracts/events/booking-expired.v1.schema.json and the envelope of ADR-0009, part F. */
class BookingExpiredMessageTest {

    private final UUID message = UUID.randomUUID();
    private final UUID booking = UUID.randomUUID();
    private final UUID event = UUID.randomUUID();
    private final UUID user = UUID.randomUUID();

    @Test
    void theEnvelopeHasEveryRequiredField() {
        String json = BookingExpiredMessage.json(message, booking, event, user,
                Instant.parse("2026-11-22T12:00:05.123Z"), Instant.parse("2026-11-22T11:59:59.999Z"), null);

        assertThat(JsonPath.<String>read(json, "$.eventId")).isEqualTo(message.toString());
        assertThat(JsonPath.<String>read(json, "$.eventType")).isEqualTo("BookingExpired");
        assertThat(JsonPath.<Integer>read(json, "$.eventVersion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(json, "$.occurredAt")).isEqualTo("2026-11-22T12:00:05Z");
        assertThat(JsonPath.<String>read(json, "$.producer")).isEqualTo("booking-service");
        assertThat(JsonPath.<Object>read(json, "$.correlationId")).isNull();
        assertThat(JsonPath.<String>read(json, "$.aggregateId")).isEqualTo(booking.toString());
        assertThat(JsonPath.<String>read(json, "$.data.bookingId")).isEqualTo(booking.toString());
        assertThat(JsonPath.<String>read(json, "$.data.eventId")).isEqualTo(event.toString());
        assertThat(JsonPath.<String>read(json, "$.data.userId")).isEqualTo(user.toString());
        assertThat(JsonPath.<String>read(json, "$.data.expiredAt")).isEqualTo("2026-11-22T11:59:59Z");
    }

    @Test
    void aCorrelationIdIsCarriedOnlyWhenItIsAUuid() {
        UUID correlation = UUID.randomUUID();

        String with = BookingExpiredMessage.json(message, booking, event, user, Instant.now(), Instant.now(),
                correlation.toString());
        String without = BookingExpiredMessage.json(message, booking, event, user, Instant.now(), Instant.now(),
                "not a uuid\" , \"x\":\"y");

        assertThat(JsonPath.<String>read(with, "$.correlationId")).isEqualTo(correlation.toString());
        assertThat(JsonPath.<Object>read(without, "$.correlationId")).isNull();
    }
}
