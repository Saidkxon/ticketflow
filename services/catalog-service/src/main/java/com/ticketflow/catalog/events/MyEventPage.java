package com.ticketflow.catalog.events;

import java.util.List;

/** One page of "my events" (ADR-0009, part D). Field names follow contracts/catalog.openapi.yaml. */
public record MyEventPage(List<EventDetail> items, int page, int size, long totalItems) {
}
