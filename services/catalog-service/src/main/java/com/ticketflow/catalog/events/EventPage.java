package com.ticketflow.catalog.events;

import java.util.List;

/** One page of the event list (ADR-0009, part D). Field names follow contracts/catalog.openapi.yaml. */
public record EventPage(List<EventSummary> items, int page, int size, long totalItems) {
}
