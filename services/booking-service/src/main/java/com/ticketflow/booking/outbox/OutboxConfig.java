package com.ticketflow.booking.outbox;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * The exchange the relay publishes to ({@code ticketflow.events}) is declared once for the whole service, by
 * {@code ProvisioningConfig}; a second bean for it would be a duplicate. Queues and their bindings belong to the
 * consumers, not to Booking (contracts/events/README.md).
 *
 * <p>{@code @EnableScheduling} switches on the periodic runs of the relay and of the expiry sweeper.
 */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OutboxProperties.class)
@EnableScheduling
class OutboxConfig {
}
