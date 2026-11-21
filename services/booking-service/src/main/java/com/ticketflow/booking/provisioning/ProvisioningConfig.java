package com.ticketflow.booking.provisioning;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The queues Booking owns for the "layout published" event (ADR-0003; contracts/events/README.md: consumers own their
 * queues). Three queues, using only what the broker provides:
 *
 * <pre>
 *   ticketflow.events --catalog.layout-published--> [main]
 *   [main]  --rejected--> [retry] (waits retryDelay) --expired--> [main]      (again)
 *   [main]  after the last attempt, or an invalid message  ------> [dlq]       (parked, for a person to look at)
 * </pre>
 *
 * The dead-letter exchange of the main and retry queues is the default (nameless) exchange, which delivers by queue
 * name, so each queue names the next one as its dead-letter routing key. Changing {@code retryDelay} for an existing
 * broker needs the retry queue to be deleted first (the broker refuses to redeclare a queue with other arguments).
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(ProvisioningProperties.class)
class ProvisioningConfig {

    static final String EXCHANGE = "ticketflow.events";
    static final String ROUTING_KEY = "catalog.layout-published";
    static final String QUEUE = "booking.catalog.layout-published";
    static final String RETRY_QUEUE = QUEUE + ".retry";
    static final String DEAD_LETTER_QUEUE = QUEUE + ".dlq";

    /** The shared exchange. Declared here too, with the same settings as Catalog's, so the binding below can exist. */
    @Bean
    TopicExchange eventsExchange() {
        return new TopicExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue layoutPublishedQueue() {
        return QueueBuilder.durable(QUEUE)
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", RETRY_QUEUE)
                .build();
    }

    @Bean
    Queue layoutPublishedRetryQueue(ProvisioningProperties properties) {
        return QueueBuilder.durable(RETRY_QUEUE)
                .withArgument("x-message-ttl", properties.retryDelay().toMillis())
                .withArgument("x-dead-letter-exchange", "")
                .withArgument("x-dead-letter-routing-key", QUEUE)
                .build();
    }

    @Bean
    Queue layoutPublishedDeadLetterQueue() {
        return QueueBuilder.durable(DEAD_LETTER_QUEUE).build();
    }

    @Bean
    Binding layoutPublishedBinding(TopicExchange eventsExchange, Queue layoutPublishedQueue) {
        return BindingBuilder.bind(layoutPublishedQueue).to(eventsExchange).with(ROUTING_KEY);
    }
}
