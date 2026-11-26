package com.ticketflow.payment.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Declares the shared exchange the relay publishes to ({@code ticketflow.events}), with the same settings as the
 * other services, so it exists even if Payment is the first to reach the broker. Queues and their bindings belong to
 * the consumers, not to Payment (contracts/events/README.md).
 *
 * <p>{@code @EnableScheduling} switches on the periodic runs of the relay.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
class OutboxConfig {

    @Bean
    TopicExchange eventsExchange(OutboxProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }
}
