package com.ticketflow.payment.refunds;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The queues Payment owns for the "refund requested" event (ADR-0003; contracts/events/README.md: consumers own their
 * queues). The same three queues as Booking's first consumer:
 *
 * <pre>
 *   ticketflow.events --booking.refund-requested--> [main]
 *   [main]  --rejected--> [retry] (waits retryDelay) --expired--> [main]      (again)
 *   [main]  after the last attempt, or an invalid message  ------> [dlq]       (parked, for a person to look at)
 * </pre>
 *
 * The exchange itself is declared by the outbox configuration (the shared {@code ticketflow.events}); only the binding
 * is made here. Changing {@code retryDelay} for an existing broker needs the retry queue to be deleted first.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(RefundProperties.class)
class RefundsConfig {

    static final String ROUTING_KEY = "booking.refund-requested";
    static final String QUEUE = "payment.booking.refund-requested";
    static final String RETRY_QUEUE = QUEUE + ".retry";
    static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";

    @Bean
    Queue refundRequestedQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", RETRY_QUEUE)
                .build();
    }

    @Bean
    Queue refundRequestedRetryQueue(RefundProperties properties) {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-message-ttl", properties.retryDelay().toMillis())
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE)
                .build();
    }

    @Bean
    Queue refundRequestedDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding refundRequestedBinding(TopicExchange eventsExchange, Queue refundRequestedQueue) {
        return BindingBuilder.bind(refundRequestedQueue).to(eventsExchange).with(ROUTING_KEY);
    }
}
