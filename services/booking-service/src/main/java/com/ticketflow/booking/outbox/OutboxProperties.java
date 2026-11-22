package com.ticketflow.booking.outbox;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings of the outbox relay (ADR-0003, rule 1).
 *
 * @param scheduleEnabled whether the relay runs by itself every {@code interval}; tests switch it off and call it directly
 * @param interval        the wait between two runs
 * @param batchSize       how many unpublished rows one run takes
 * @param confirmTimeout  how long to wait for the broker to confirm one message
 * @param exchange        the topic exchange all events are published to; consumers bind their own queues to it
 */
@ConfigurationProperties("ticketflow.booking.outbox")
public record OutboxProperties(
        @DefaultValue("true") boolean scheduleEnabled,
        @DefaultValue("PT1S") Duration interval,
        @DefaultValue("50") int batchSize,
        @DefaultValue("PT5S") Duration confirmTimeout,
        @DefaultValue("ticketflow.events") String exchange) {
}
