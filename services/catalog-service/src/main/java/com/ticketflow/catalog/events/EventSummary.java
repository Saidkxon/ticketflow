package com.ticketflow.catalog.events;

import java.time.Instant;
import java.util.UUID;

/** One item of the event list. Field names follow contracts/catalog.openapi.yaml. */
public record EventSummary(UUID id, String title, Instant startsAt) {
}
