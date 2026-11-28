package com.ticketflow.booking.payments;

import java.util.Arrays;
import java.util.Optional;

/**
 * The events of Payment that Booking consumes (ADR-0006; contracts/events/README.md). Each has its own queue, named
 * "consumer.routing key", with a retry queue and a dead-letter queue next to it (ADR-0003 rule 4).
 */
enum PaymentEventKind {
    PAYMENT_SUCCEEDED("PaymentSucceeded", "payment.payment-succeeded"),
    PAYMENT_FAILED("PaymentFailed", "payment.payment-failed"),
    REFUND_SUCCEEDED("RefundSucceeded", "payment.refund-succeeded");

    private final String eventType;
    private final String routingKey;

    PaymentEventKind(String eventType, String routingKey) {
        this.eventType = eventType;
        this.routingKey = routingKey;
    }

    String eventType() {
        return eventType;
    }

    String routingKey() {
        return routingKey;
    }

    String queue() {
        return "booking." + routingKey;
    }

    String retryQueue() {
        return queue() + ".retry";
    }

    String deadLetterQueue() {
        return queue() + ".dlq";
    }

    static Optional<PaymentEventKind> forQueue(String queue) {
        return Arrays.stream(values()).filter(kind -> kind.queue().equals(queue)).findFirst();
    }

    static String[] queues() {
        return Arrays.stream(values()).map(PaymentEventKind::queue).toArray(String[]::new);
    }
}
