package com.ticketflow.catalog.events;

import java.time.Instant;
import java.util.UUID;

/**
 * An event as its organizer sees it. The status is DRAFT or PUBLISHED. The version is the optimistic-locking
 * counter (ADR-0005): it goes out as the ETag, and an update must send it back in If-Match (ADR-0009).
 * Field names follow contracts/catalog.openapi.yaml.
 */
public record EventDetail(UUID id, String title, Instant startsAt, String venueName, String city,
        Double venueLatitude, Double venueLongitude, String status, long version) {
}
