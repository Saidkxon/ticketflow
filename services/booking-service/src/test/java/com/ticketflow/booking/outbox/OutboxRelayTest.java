package com.ticketflow.booking.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * Booking's outbox relay against the real RabbitMQ of the shared test container (ADR-0003; keep its image tag equal to
 * deploy/compose.yaml). Every test uses its own event type and queue, so tests cannot see each other's messages.
 */
class OutboxRelayTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private OutboxRelay relay;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private OutboxProperties properties;

    @BeforeEach
    void emptyTheOutbox() {
        // Other test classes leave unpublished rows behind; the relay would meet them first.
        jdbc.update("DELETE FROM outbox_events");
    }

    // ---- routing keys

    @Test
    void theRoutingKeyIsTheProducerAndTheEventNameInLowerCase() {
        assertThat(OutboxRelay.routingKey("BookingExpired")).isEqualTo("booking.booking-expired");
        assertThat(OutboxRelay.routingKey("BookingConfirmed")).isEqualTo("booking.booking-confirmed");
        assertThat(OutboxRelay.routingKey("Ping")).isEqualTo("booking.ping");
    }

    // ---- delivery

    @Test
    void aPendingEventReachesABoundQueueWithTheExpectedProperties() {
        String type = "RelayCheckOne";
        String queue = bindQueueFor(type);
        UUID id = UUID.randomUUID();
        UUID aggregate = UUID.randomUUID();
        UUID correlation = UUID.randomUUID();
        String payload = envelope(id, type, aggregate, correlation.toString());
        outbox.insert(id, "TestEvent", aggregate, type, payload);

        OutboxRelay.Outcome outcome = relay.runOnce();

        assertThat(outcome.failed()).isFalse();
        assertThat(outcome.published()).isEqualTo(1);
        assertThat(outcome.unroutable()).isZero();
        Message message = rabbit.receive(queue, 5000);
        assertThat(message).isNotNull();
        assertThat(new String(message.getBody(), StandardCharsets.UTF_8)).contains(id.toString()).contains(type);
        assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        assertThat(message.getMessageProperties().getType()).isEqualTo(type);
        assertThat(message.getMessageProperties().getCorrelationId()).isEqualTo(correlation.toString());
        assertThat(message.getMessageProperties().getContentType()).isEqualTo("application/json");
        assertThat(message.getMessageProperties().getReceivedDeliveryMode()).isEqualTo(MessageDeliveryMode.PERSISTENT);
        assertThat(message.getMessageProperties().getReceivedRoutingKey()).isEqualTo(OutboxRelay.routingKey(type));
        assertThat(isPublished(id)).isTrue();
    }

    @Test
    void aPublishedEventIsNotSentAgain() {
        String type = "RelayCheckTwo";
        String queue = bindQueueFor(type);
        UUID id = UUID.randomUUID();
        outbox.insert(id, "TestEvent", UUID.randomUUID(), type, envelope(id, type, UUID.randomUUID(), null));

        relay.runOnce();
        relay.runOnce();

        assertThat(rabbit.receive(queue, 5000)).isNotNull();
        assertThat(rabbit.receive(queue, 500)).isNull();
    }

    @Test
    void eventsAreSentInTheOrderTheyWereWritten() {
        String type = "RelayCheckThree";
        String queue = bindQueueFor(type);
        List<UUID> ids = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        for (UUID id : ids) {
            outbox.insert(id, "TestEvent", UUID.randomUUID(), type, envelope(id, type, UUID.randomUUID(), null));
        }

        relay.runOnce();

        for (UUID id : ids) {
            Message message = rabbit.receive(queue, 5000);
            assertThat(message).isNotNull();
            assertThat(message.getMessageProperties().getMessageId()).isEqualTo(id.toString());
        }
    }

    // ---- nobody is listening yet, and the broker is down

    @Test
    void anEventNobodyReceivesStaysInTheOutboxAndIsDeliveredWhenAQueueAppears() {
        String type = "RelayCheckFour";
        UUID id = UUID.randomUUID();
        outbox.insert(id, "TestEvent", UUID.randomUUID(), type, envelope(id, type, UUID.randomUUID(), null));

        OutboxRelay.Outcome first = relay.runOnce();

        assertThat(first.failed()).isFalse();
        assertThat(first.published()).isZero();
        assertThat(first.unroutable()).isEqualTo(1);
        assertThat(isPublished(id)).isFalse();

        String queue = bindQueueFor(type);
        relay.runOnce();

        assertThat(rabbit.receive(queue, 5000)).isNotNull();
        assertThat(isPublished(id)).isTrue();
    }

    @Test
    void anEventNobodyReceivesDoesNotHoldBackTheEventsBehindIt() {
        String waitingType = "RelayCheckSix";
        String receivedType = "RelayCheckSeven";
        UUID waiting = UUID.randomUUID();
        UUID received = UUID.randomUUID();
        outbox.insert(waiting, "TestEvent", UUID.randomUUID(), waitingType,
                envelope(waiting, waitingType, UUID.randomUUID(), null));
        outbox.insert(received, "TestEvent", UUID.randomUUID(), receivedType,
                envelope(received, receivedType, UUID.randomUUID(), null));
        String queue = bindQueueFor(receivedType);

        OutboxRelay.Outcome outcome = relay.runOnce();

        assertThat(outcome.published()).isEqualTo(1);
        assertThat(outcome.unroutable()).isEqualTo(1);
        assertThat(rabbit.receive(queue, 5000)).isNotNull();
        assertThat(isPublished(received)).isTrue();
        assertThat(isPublished(waiting)).isFalse();
    }

    @Test
    void whenTheBrokerIsDownNothingIsLostAndNothingIsThrown() {
        String type = "RelayCheckFive";
        UUID id = UUID.randomUUID();
        outbox.insert(id, "TestEvent", UUID.randomUUID(), type, envelope(id, type, UUID.randomUUID(), null));
        // A broker address where nothing listens.
        CachingConnectionFactory unreachable = new CachingConnectionFactory("127.0.0.1", 1);
        unreachable.setPublisherReturns(true);
        unreachable.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
        RabbitTemplate brokenTemplate = new RabbitTemplate(unreachable);
        brokenTemplate.setMandatory(true);
        OutboxRelay brokenRelay = new OutboxRelay(repositoryOf(), brokenTemplate, transactions, properties);

        OutboxRelay.Outcome outcome = brokenRelay.runOnce();

        assertThat(outcome.failed()).isTrue();
        assertThat(outcome.published()).isZero();
        assertThat(outcome.failure()).doesNotContain("127.0.0.1");
        assertThat(isPublished(id)).isFalse();
        unreachable.destroy();
    }

    // ---- helpers

    private OutboxRepository repositoryOf() {
        return outbox;
    }

    /**
     * A queue that receives the events of this type. It is durable because RabbitMQ 4.x no longer accepts transient
     * queues that are not exclusive; the container is thrown away when the tests end, so nothing is cleaned up.
     */
    private String bindQueueFor(String eventType) {
        String name = "test." + eventType + "." + UUID.randomUUID();
        TopicExchange exchange = new TopicExchange(properties.exchange(), true, false);
        admin.declareExchange(exchange);
        Queue queue = new Queue(name, true, false, false);
        admin.declareQueue(queue);
        admin.declareBinding(BindingBuilder.bind(queue).to(exchange).with(OutboxRelay.routingKey(eventType)));
        return name;
    }

    private boolean isPublished(UUID id) {
        return jdbc.queryForObject("SELECT published_at IS NOT NULL FROM outbox_events WHERE id = ?", Boolean.class, id);
    }

    private static String envelope(UUID id, String type, UUID aggregate, String correlationId) {
        return """
                {"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-11-21T10:00:00Z",\
                "producer":"booking-service","correlationId":%s,"aggregateId":"%s","data":{"bookingId":"%s"}}\
                """.formatted(id, type, correlationId == null ? "null" : "\"" + correlationId + "\"", aggregate, aggregate);
    }
}
