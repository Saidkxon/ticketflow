package com.ticketflow.booking.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
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
 * The consumer of Payment's events against a real broker (ADR-0003, ADR-0006 R2): each event reaches its handler path
 * from its own queue, a message delivered twice is recorded once, and a message that can never work is parked, not
 * lost and not looped. Until the saga steps exist the handler only logs, so the tests look at the processed-events table.
 */
class PaymentEventListenerTest extends AbstractPostgresIntegrationTest {

    private static final String EXCHANGE = "ticketflow.events";

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private JdbcTemplate jdbc;

    /** The queues are declared when the consumer connects; a message sent before that would have nowhere to go. */
    @BeforeEach
    void waitForTheConsumers() {
        for (PaymentEventKind kind : PaymentEventKind.values()) {
            assertThat(eventually(30, () -> {
                QueueInformation info = admin.getQueueInfo(kind.queue());
                return info != null && info.getConsumerCount() >= 1;
            })).as("the consumer of " + kind.queue() + " is connected").isTrue();
        }
    }

    // ---- the normal path

    @Test
    void eachEventOfPaymentIsReceivedFromItsOwnQueue() {
        UUID succeeded = UUID.randomUUID();
        UUID failed = UUID.randomUUID();
        UUID refunded = UUID.randomUUID();

        publish(PaymentEventKind.PAYMENT_SUCCEEDED, message(succeeded, PaymentEventKind.PAYMENT_SUCCEEDED));
        publish(PaymentEventKind.PAYMENT_FAILED, message(failed, PaymentEventKind.PAYMENT_FAILED));
        publish(PaymentEventKind.REFUND_SUCCEEDED, message(refunded, PaymentEventKind.REFUND_SUCCEEDED));

        assertThat(eventually(30, () -> recorded(succeeded) == 1 && recorded(failed) == 1 && recorded(refunded) == 1))
                .isTrue();
        assertThat(typeRecorded(succeeded)).isEqualTo("PaymentSucceeded");
        assertThat(typeRecorded(failed)).isEqualTo("PaymentFailed");
        assertThat(typeRecorded(refunded)).isEqualTo("RefundSucceeded");
    }

    @Test
    void theSameMessageTwiceIsRecordedOnceAndNothingIsParked() {
        UUID eventId = UUID.randomUUID();
        String body = message(eventId, PaymentEventKind.PAYMENT_SUCCEEDED);

        publish(PaymentEventKind.PAYMENT_SUCCEEDED, body);
        publish(PaymentEventKind.PAYMENT_SUCCEEDED, body);
        // A different event sent after the copy is handled once both copies are through the queue (one consumer thread
        // per queue takes them in order).
        UUID marker = UUID.randomUUID();
        publish(PaymentEventKind.PAYMENT_SUCCEEDED, message(marker, PaymentEventKind.PAYMENT_SUCCEEDED));

        assertThat(eventually(30, () -> recorded(marker) == 1)).isTrue();
        assertThat(recorded(eventId)).isEqualTo(1);
        assertThat(rabbit.receive(PaymentEventKind.PAYMENT_SUCCEEDED.deadLetterQueue(), 300)).isNull();
    }

    @Test
    void aMessageFromTheQueueOfAnotherEventIsParkedNotHandled() {
        UUID eventId = UUID.randomUUID();

        publish(PaymentEventKind.PAYMENT_SUCCEEDED, message(eventId, PaymentEventKind.PAYMENT_FAILED));

        Message parked = awaitParked(PaymentEventKind.PAYMENT_SUCCEEDED, eventId.toString());
        assertThat(parked).isNotNull();
        assertThat(failureOf(parked)).contains("not a PaymentSucceeded");
        assertThat(recorded(eventId)).isZero();
    }

    // ---- messages that can never work

    @Test
    void aMessageThatIsNotJsonIsParkedAtOnce() {
        String marker = "not-json-" + UUID.randomUUID();

        publish(PaymentEventKind.PAYMENT_FAILED, marker);

        Message parked = awaitParked(PaymentEventKind.PAYMENT_FAILED, marker);
        assertThat(parked).isNotNull();
        assertThat(failureOf(parked)).contains("not valid JSON");
    }

    @Test
    void aMessageOfAnotherVersionIsParkedAtOnce() {
        UUID eventId = UUID.randomUUID();

        publish(PaymentEventKind.PAYMENT_SUCCEEDED,
                message(eventId, PaymentEventKind.PAYMENT_SUCCEEDED).replace("\"eventVersion\":1", "\"eventVersion\":2"));

        assertThat(awaitParked(PaymentEventKind.PAYMENT_SUCCEEDED, eventId.toString())).isNotNull();
        assertThat(recorded(eventId)).isZero();
    }

    @Test
    void aFailureWithoutAReasonAndAMessageWithoutAPaymentAreParkedAtOnce() {
        UUID noReason = UUID.randomUUID();
        UUID noPayment = UUID.randomUUID();

        publish(PaymentEventKind.PAYMENT_FAILED,
                message(noReason, PaymentEventKind.PAYMENT_FAILED).replace(",\"reason\":\"card_declined\"", ""));
        publish(PaymentEventKind.PAYMENT_SUCCEEDED,
                message(noPayment, PaymentEventKind.PAYMENT_SUCCEEDED).replaceAll("\"paymentId\":\"[^\"]*\",", ""));

        assertThat(awaitParked(PaymentEventKind.PAYMENT_FAILED, noReason.toString())).isNotNull();
        assertThat(awaitParked(PaymentEventKind.PAYMENT_SUCCEEDED, noPayment.toString())).isNotNull();
        assertThat(recorded(noReason)).isZero();
        assertThat(recorded(noPayment)).isZero();
    }

    // ---- counting attempts

    @Test
    void theAttemptNumberComesFromTheBrokersDeathRecordOfThatQueue() {
        String queue = PaymentEventKind.PAYMENT_SUCCEEDED.queue();
        MessageProperties properties = new MessageProperties();
        assertThat(PaymentEventListener.attemptOf(properties, queue)).isEqualTo(1);

        properties.setHeader("x-death", List.of(
                Map.of("queue", queue, "reason", "rejected", "count", 2L),
                Map.of("queue", PaymentEventKind.PAYMENT_SUCCEEDED.retryQueue(), "reason", "expired", "count", 2L),
                Map.of("queue", PaymentEventKind.PAYMENT_FAILED.queue(), "reason", "rejected", "count", 9L)));

        assertThat(PaymentEventListener.attemptOf(properties, queue)).isEqualTo(3);
    }

    @Test
    void eachEventHasItsOwnQueueRetryQueueAndDeadLetterQueue() {
        assertThat(PaymentEventKind.PAYMENT_SUCCEEDED.queue()).isEqualTo("booking.payment.payment-succeeded");
        assertThat(PaymentEventKind.PAYMENT_FAILED.retryQueue()).isEqualTo("booking.payment.payment-failed.retry");
        assertThat(PaymentEventKind.REFUND_SUCCEEDED.deadLetterQueue()).isEqualTo("booking.payment.refund-succeeded.dlq");
        assertThat(PaymentEventKind.forQueue("booking.payment.refund-succeeded")).contains(PaymentEventKind.REFUND_SUCCEEDED);
        assertThat(PaymentEventKind.forQueue("somewhere.else")).isEmpty();
    }

    // ---- helpers

    private void publish(PaymentEventKind kind, String body) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setMessageId(UUID.randomUUID().toString());
        rabbit.send(EXCHANGE, kind.routingKey(), new Message(body.getBytes(StandardCharsets.UTF_8), properties));
    }

    private static String message(UUID eventId, PaymentEventKind kind) {
        String reason = kind == PaymentEventKind.PAYMENT_SUCCEEDED ? ""
                : kind == PaymentEventKind.PAYMENT_FAILED ? ",\"reason\":\"card_declined\""
                : ",\"reason\":\"booking_cancelled\"";
        UUID payment = UUID.randomUUID();
        return "{\"eventId\":\"" + eventId + "\",\"eventType\":\"" + kind.eventType() + "\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-28T10:00:00Z\",\"producer\":\"payment-service\",\"correlationId\":null,"
                + "\"aggregateId\":\"" + payment + "\",\"data\":{\"paymentId\":\"" + payment + "\","
                + "\"bookingId\":\"" + UUID.randomUUID() + "\",\"attemptNumber\":1,\"amountMinor\":150000,"
                + "\"currency\":\"UZS\"" + reason + "}}";
    }

    private static String failureOf(Message parked) {
        return String.valueOf(parked.getMessageProperties().getHeaders().get("failure"));
    }

    /** The message in this event's dead-letter queue whose body contains {@code marker}; others met on the way are dropped. */
    private Message awaitParked(PaymentEventKind kind, String marker) {
        long deadline = System.currentTimeMillis() + 30_000;
        while (System.currentTimeMillis() < deadline) {
            Message message = rabbit.receive(kind.deadLetterQueue(), 1000);
            if (message != null && new String(message.getBody(), StandardCharsets.UTF_8).contains(marker)) {
                return message;
            }
        }
        return null;
    }

    private int recorded(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class, PaymentEventProcessor.CONSUMER, eventId);
    }

    private String typeRecorded(UUID eventId) {
        return jdbc.queryForObject("SELECT event_type FROM processed_events WHERE event_id = ?", String.class, eventId);
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
