package com.ticketflow.payment.refunds;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.payment.AbstractPostgresIntegrationTest;
import com.ticketflow.payment.payments.Payment;
import com.ticketflow.payment.payments.PaymentAttemptService;
import com.ticketflow.payment.payments.PaymentProcessor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AmqpAdmin;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageBuilder;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The refund consumer against the real RabbitMQ of the shared test container (ADR-0003, ADR-0006 R4): a request that
 * arrives starts the refund, the same request twice starts one, and a message that can never work is parked in the
 * dead-letter queue instead of blocking the queue or disappearing.
 */
class RefundRequestedListenerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RabbitTemplate rabbit;

    @Autowired
    private AmqpAdmin admin;

    @Autowired
    private PaymentAttemptService attempts;

    @Autowired
    private PaymentProcessor processor;

    @Autowired
    private JdbcTemplate jdbc;

    /**
     * The queues are declared when the consumer connects, which happens a moment after the context starts. A message
     * published before that has no queue to go to and is dropped (the template is "mandatory"), so each test waits.
     */
    @BeforeEach
    void startEmpty() throws InterruptedException {
        boolean connected = false;
        for (int i = 0; i < 300 && !connected; i++) {
            org.springframework.amqp.core.QueueInformation info = admin.getQueueInfo(RefundsConfig.QUEUE);
            connected = info != null && info.getConsumerCount() >= 1;
            if (!connected) {
                Thread.sleep(100);
            }
        }
        assertThat(connected).as("the refund consumer is connected").isTrue();
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM sim_provider_charges");
        admin.purgeQueue(RefundsConfig.QUEUE, true);
        admin.purgeQueue(RefundsConfig.RETRY_QUEUE, true);
        admin.purgeQueue(RefundsConfig.DEAD_LETTER_QUEUE, true);
    }

    private Payment succeededPayment() {
        Payment payment = attempts.create(UUID.randomUUID(), 1, 150_000, "UZS", "4242424242424242");
        processor.runOnce();
        return payment;
    }

    private static String refundRequested(UUID paymentId, UUID bookingId, String reason) {
        return "{\"eventId\":\"" + UUID.randomUUID() + "\",\"eventType\":\"RefundRequested\",\"eventVersion\":1,"
                + "\"occurredAt\":\"2026-11-27T10:00:00Z\",\"producer\":\"booking-service\",\"correlationId\":null,"
                + "\"aggregateId\":\"" + bookingId + "\",\"data\":{\"paymentId\":\"" + paymentId + "\","
                + "\"bookingId\":\"" + bookingId + "\",\"reason\":\"" + reason + "\"}}";
    }

    private void publish(String body) {
        Message message = MessageBuilder.withBody(body.getBytes(StandardCharsets.UTF_8))
                .setContentType("application/json")
                .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                .setMessageId(UUID.randomUUID().toString())
                .build();
        rabbit.send("ticketflow.events", RefundsConfig.ROUTING_KEY, message);
    }

    private String statusOf(Payment payment) {
        return jdbc.queryForObject("select status from payments where payment_id = ?", String.class, payment.paymentId());
    }

    private void waitForStatus(Payment payment, String wanted) throws InterruptedException {
        for (int i = 0; i < 100 && !wanted.equals(statusOf(payment)); i++) {
            Thread.sleep(100);
        }
        assertThat(statusOf(payment)).isEqualTo(wanted);
    }

    @Test
    void aRefundRequestStartsTheRefundAndTheProcessorMakesIt() throws Exception {
        Payment payment = succeededPayment();

        publish(refundRequested(payment.paymentId(), payment.bookingId(), "booking_cancelled"));
        waitForStatus(payment, "REFUND_PENDING");
        processor.runOnce();

        assertThat(statusOf(payment)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? "
                + "and event_type = 'RefundSucceeded'", Integer.class, payment.paymentId())).isEqualTo(1);
    }

    @Test
    void theSameRequestDeliveredTwiceStartsOneRefund() throws Exception {
        Payment payment = succeededPayment();
        String body = refundRequested(payment.paymentId(), payment.bookingId(), "booking_expired");

        publish(body);
        publish(body);
        waitForStatus(payment, "REFUND_PENDING");
        Thread.sleep(500);
        processor.runOnce();

        assertThat(statusOf(payment)).isEqualTo("REFUNDED");
        assertThat(jdbc.queryForObject("select count(*) from outbox_events where aggregate_id = ? "
                + "and event_type = 'RefundSucceeded'", Integer.class, payment.paymentId())).isEqualTo(1);
        assertThat(rabbit.receive(RefundsConfig.DEAD_LETTER_QUEUE, 500)).isNull();
    }

    @Test
    void aMessageThatIsNotARefundRequestIsParkedAtOnce() {
        publish("this is not json");

        Message parked = rabbit.receive(RefundsConfig.DEAD_LETTER_QUEUE, 10_000);

        assertThat(parked).isNotNull();
        assertThat(new String(parked.getBody(), StandardCharsets.UTF_8)).isEqualTo("this is not json");
        assertThat(String.valueOf(parked.getMessageProperties().getHeaders().get("failure")))
                .contains("not valid JSON");
    }

    @Test
    void aRequestForAPaymentThatDoesNotExistOrWasNeverChargedIsParkedAtOnce() {
        UUID booking = UUID.randomUUID();
        publish(refundRequested(UUID.randomUUID(), booking, "booking_expired"));
        Payment failed = attempts.create(UUID.randomUUID(), 1, 150_000, "UZS", "4000000000000002");
        processor.runOnce();
        publish(refundRequested(failed.paymentId(), failed.bookingId(), "booking_expired"));

        Message first = rabbit.receive(RefundsConfig.DEAD_LETTER_QUEUE, 10_000);
        Message second = rabbit.receive(RefundsConfig.DEAD_LETTER_QUEUE, 10_000);

        assertThat(first).isNotNull();
        assertThat(second).isNotNull();
        assertThat(jdbc.queryForObject("select status from payments where payment_id = ?", String.class,
                failed.paymentId())).isEqualTo("FAILED");
    }

    @Test
    void theAttemptNumberCountsTheTimesTheBrokerMovedTheMessageToTheRetryQueue() {
        org.springframework.amqp.core.MessageProperties properties = new org.springframework.amqp.core.MessageProperties();
        assertThat(RefundRequestedListener.attemptOf(properties)).isEqualTo(1);

        properties.getHeaders().put("x-death", java.util.List.of(java.util.Map.of(
                "queue", RefundsConfig.QUEUE, "reason", "rejected", "count", 3L)));
        assertThat(RefundRequestedListener.attemptOf(properties)).isEqualTo(4);
    }
}
