package com.ticketflow.booking.payments;

import java.util.ArrayList;
import java.util.List;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.Declarable;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * The queues Booking owns for the events of Payment (ADR-0003; contracts/events/README.md: consumers own their queues).
 * For each event the same three queues as for the first consumer:
 *
 * <pre>
 *   ticketflow.events --payment.payment-succeeded--> [main]
 *   [main]  --rejected--> [retry] (waits retryDelay) --expired--> [main]      (again)
 *   [main]  after the last attempt, or an invalid message  ------> [dlq]       (parked, for a person to look at)
 * </pre>
 *
 * The exchange itself is declared by the provisioning configuration (the shared {@code ticketflow.events}); only the
 * bindings are made here. Changing {@code retryDelay} for an existing broker needs the retry queues to be deleted first.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(PaymentEventsProperties.class)
class PaymentEventsConfig {

    @Bean
    Declarables paymentEventQueues(TopicExchange eventsExchange, PaymentEventsProperties properties) {
        List<Declarable> declarables = new ArrayList<>();
        for (PaymentEventKind kind : PaymentEventKind.values()) {
            Queue main = QueueBuilder.durable(kind.queue())
                    .withArgument("x-dead-letter-exchange", "")
                    .withArgument("x-dead-letter-routing-key", kind.retryQueue())
                    .build();
            Queue retry = QueueBuilder.durable(kind.retryQueue())
                    .withArgument("x-message-ttl", properties.retryDelay().toMillis())
                    .withArgument("x-dead-letter-exchange", "")
                    .withArgument("x-dead-letter-routing-key", kind.queue())
                    .build();
            Queue deadLetter = QueueBuilder.durable(kind.deadLetterQueue()).build();
            declarables.add(main);
            declarables.add(retry);
            declarables.add(deadLetter);
            declarables.add(BindingBuilder.bind(main).to(eventsExchange).with(kind.routingKey()));
        }
        return new Declarables(declarables);
    }
}
