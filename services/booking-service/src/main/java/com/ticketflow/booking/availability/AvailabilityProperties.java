package com.ticketflow.booking.availability;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * @param cacheTtl how long the list of unavailable seats of an event is reused before the database is asked again
 *                 (ADR-0011 and ADR-0012 allow one or two seconds); zero turns the cache off
 */
@ConfigurationProperties("ticketflow.booking.availability")
public record AvailabilityProperties(@DefaultValue("2s") Duration cacheTtl) {
}
