package com.ticketflow.booking.provisioning;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import com.ticketflow.booking.CatalogStandIn;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.core.QueueInformation;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The consumer against a real broker (ADR-0003, ADR-0012 test obligation 5): a published layout puts an event on sale,
 * a Catalog that is down for a while only delays it, and a message that cannot work is parked, not lost and not looped.
 * The tests use short waits (see AbstractPostgresIntegrationTest): 3 attempts, 1 second apart.
 */
class LayoutPublishedListenerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private JdbcTemplate jdbc;

    /** The queues are declared when the consumer connects; a message sent before that would have nowhere to go. */
    @BeforeEach
    void waitForTheConsumer() {
        assertThat(eventually(30, () -> {
            QueueInformation info = admin.getQueueInfo(ProvisioningConfig.QUEUE);
            return info != null && info.getConsumerCount() >= 1;
        })).as("the consumer is connected").isTrue();
    }

    // ---- the normal path

    @Test
    void aPublishedLayoutPutsTheEventOnSale() {
        LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
        CatalogStandIn.serve(layout.eventId, layout.json());

        publish(envelope(layout.eventId, 1));

        assertThat(eventually(30, () -> inventoryCount(layout.eventId) == 1)).isTrue();
        assertThat(seatCount(layout.eventId)).isEqualTo(LayoutFixtures.SEATS);
    }

    @Test
    void theSameMessageTwiceCreatesOneInventory() {
        LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
        CatalogStandIn.serve(layout.eventId, layout.json());
        String message = envelope(layout.eventId, 1);

        publish(message);
        assertThat(eventually(30, () -> inventoryCount(layout.eventId) == 1)).isTrue();
        publish(message);
        // The second copy is handled: Catalog is asked again, and the database already has the event.
        assertThat(eventually(30, () -> CatalogStandIn.requestsFor(layout.eventId) >= 2)).isTrue();

        assertThat(inventoryCount(layout.eventId)).isEqualTo(1);
        assertThat(seatCount(layout.eventId)).isEqualTo(LayoutFixtures.SEATS);
    }

    // ---- Catalog is not available

    @Test
    void aCatalogThatIsDownForAWhileOnlyDelaysTheEvent() {
        LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
        CatalogStandIn.serve(layout.eventId, layout.json());
        CatalogStandIn.failNext(layout.eventId, 2);

        publish(envelope(layout.eventId, 1));

        assertThat(eventually(30, () -> inventoryCount(layout.eventId) == 1)).isTrue();
        assertThat(CatalogStandIn.requestsFor(layout.eventId)).isEqualTo(3);
        assertThat(seatCount(layout.eventId)).isEqualTo(LayoutFixtures.SEATS);
    }

    @Test
    void aCatalogThatStaysDownParksTheMessageAfterTheLastAttempt() {
        LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
        CatalogStandIn.serve(layout.eventId, layout.json());
        CatalogStandIn.failNext(layout.eventId, Integer.MAX_VALUE);

        publish(envelope(layout.eventId, 1));

        Message parked = awaitParked(layout.eventId.toString());
        assertThat(parked).as("the message reaches the dead-letter queue").isNotNull();
        assertThat(parked.getMessageProperties().getHeaders().get("failure").toString()).contains("failed 3 times");
        assertThat(CatalogStandIn.requestsFor(layout.eventId)).isEqualTo(3);
        assertThat(inventoryCount(layout.eventId)).isZero();
    }

    // ---- messages that can never work

    @Test
    void aMessageThatIsNotJsonIsParkedAtOnce() {
        String marker = "not-json-" + UUID.randomUUID();

        publish(marker);

        Message parked = awaitParked(marker);
        assertThat(parked).isNotNull();
        assertThat(parked.getMessageProperties().getHeaders().get("failure").toString()).contains("not valid JSON");
    }

    @Test
    void aLayoutOfAnotherVersionThanTheMessageSaysIsParkedAtOnce() {
        LayoutFixtures layout = new LayoutFixtures(UUID.randomUUID());
        layout.layoutVersion = 1;
        CatalogStandIn.serve(layout.eventId, layout.json());

        publish(envelope(layout.eventId, 2));

        Message parked = awaitParked(layout.eventId.toString());
        assertThat(parked).isNotNull();
        assertThat(parked.getMessageProperties().getHeaders().get("failure").toString()).contains("version");
        assertThat(CatalogStandIn.requestsFor(layout.eventId)).isEqualTo(1);
        assertThat(inventoryCount(layout.eventId)).isZero();
    }

    @Test
    void aMessageOfAnotherTypeOrVersionIsParkedAtOnce() {
        UUID eventId = UUID.randomUUID();

        publish(envelope(eventId, 1).replace("\"eventVersion\":1", "\"eventVersion\":2"));

        assertThat(awaitParked(eventId.toString())).isNotNull();
        assertThat(CatalogStandIn.requestsFor(eventId)).isZero();
    }

    // ---- counting attempts

    @Test
    void theAttemptNumberComesFromTheBrokersDeathRecord() {
        MessageProperties properties = new MessageProperties();
        assertThat(LayoutPublishedListener.attemptOf(properties)).isEqualTo(1);

        properties.setHeader("x-death", List.of(
                Map.of("queue", ProvisioningConfig.QUEUE, "reason", "rejected", "count", 2L),
                Map.of("queue", ProvisioningConfig.RETRY_QUEUE, "reason", "expired", "count", 2L),
                Map.of("queue", "some.other.queue", "reason", "rejected", "count", 9L)));

        assertThat(LayoutPublishedListener.attemptOf(properties)).isEqualTo(3);
    }

    // ---- helpers

    private void publish(String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        rabbit.send(ProvisioningConfig.EXCHANGE, ProvisioningConfig.ROUTING_KEY,
                new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private static String envelope(UUID eventId, long layoutVersion) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"LayoutPublished\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-22T10:00:00Z\",\"producer\":\"catalog-service\",\"correlationId\":null,"
                + "\"aggregateId\":\"" + eventId + "\",\"data\":{\"eventId\":\"" + eventId
                + "\",\"layoutVersion\":" + layoutVersion + "}}";
    }

    /** The message in the dead-letter queue whose body contains {@code marker}; others met on the way are dropped. */
    private Message awaitParked(String marker) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbit.receive(ProvisioningConfig.DEAD_LETTER_QUEUE, 1000);
            if (message != null && new String(message.getBody(), StandardCharsets.UTF_8).contains(marker)) {
                return message;
            }
        }
        return null;
    }

    private int inventoryCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_inventory WHERE event_id = ?", Integer.class, eventId);
    }

    private int seatCount(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM event_seats WHERE event_id = ?", Integer.class, eventId);
    }

    private static boolean eventually(int seconds, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + seconds * 1000L;
        while (System.currentTimeMillis() < deadline) {
            try {
                if (condition.getAsBoolean()) {
                    return true;
                }
                Thread.sleep(100);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return false;
            }
        }
        return false;
    }
}
