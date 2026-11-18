package com.ticketflow.catalog.events;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The envelope of the "layout published" message (ADR-0009, part F), checked as the consumer will read it. */
class LayoutPublishedMessageTest {

    private static final UUID MESSAGE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID EVENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final String CORRELATION = "33333333-3333-4333-8333-333333333333";
    private static final Instant NOW = Instant.parse("2026-11-17T10:15:30.987Z");

    @Test
    void theEnvelopeHasEveryRequiredField() {
        String json = LayoutPublishedMessage.json(MESSAGE, EVENT, 4, NOW, CORRELATION);

        assertThat((String) JsonPath.read(json, "$.eventId")).isEqualTo(MESSAGE.toString());
        assertThat((String) JsonPath.read(json, "$.eventType")).isEqualTo("LayoutPublished");
        assertThat((Integer) JsonPath.read(json, "$.eventVersion")).isEqualTo(1);
        assertThat((String) JsonPath.read(json, "$.occurredAt")).isEqualTo("2026-11-17T10:15:30Z");
        assertThat((String) JsonPath.read(json, "$.producer")).isEqualTo("catalog-service");
        assertThat((String) JsonPath.read(json, "$.correlationId")).isEqualTo(CORRELATION);
        assertThat((String) JsonPath.read(json, "$.aggregateId")).isEqualTo(EVENT.toString());
        assertThat((String) JsonPath.read(json, "$.data.eventId")).isEqualTo(EVENT.toString());
        assertThat((Integer) JsonPath.read(json, "$.data.layoutVersion")).isEqualTo(4);
    }

    @Test
    void aMissingCorrelationIdIsNull() {
        String json = LayoutPublishedMessage.json(MESSAGE, EVENT, 1, NOW, null);

        assertThat((Object) JsonPath.read(json, "$.correlationId")).isNull();
    }

    @Test
    void aCorrelationIdThatIsNotAUuidIsNeverCopiedIntoTheJson() {
        String json = LayoutPublishedMessage.json(MESSAGE, EVENT, 1, NOW, "x\",\"evil\":\"1");

        assertThat((Object) JsonPath.read(json, "$.correlationId")).isNull();
        assertThat(json).doesNotContain("evil");
    }
}
