package com.ticketflow.catalog.outbox;

import org.springframework.amqp.core.TopicExchange;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
class OutboxConfig {

    /**
     * The one exchange all services publish their events to (contracts/events/README.md). It is declared when the
     * relay first connects to the broker. Queues and their bindings belong to the consumers, not to Catalog.
     */
    @Bean
    TopicExchange eventsExchange(OutboxProperties properties) {
        return new TopicExchange(properties.exchange(), true, false);
    }
}
