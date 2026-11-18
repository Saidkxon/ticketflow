package com.ticketflow.catalog.events;

import java.util.List;
import java.util.UUID;

/**
 * The frozen layout of a published event, with every seat and its ID. The same document serves the seat map in
 * the browser and the snapshot that Booking fetches after a "layout published" event (ADR-0012, part A). It never
 * changes for a layout version. Field names follow contracts/catalog.openapi.yaml.
 *
 * <p>{@code layoutVersion} is the event version at the moment of publishing; a published event cannot be
 * edited, so it stays the same.
 */
public record PublishedLayout(UUID eventId, long layoutVersion, String currency,
        List<LayoutDetail.PriceTierView> priceTiers, List<Section> sections, int totalSeats) {

    /** {@code tierId} points into {@code priceTiers}; {@code tier} is that tier's name. */
    public record Section(UUID id, String name, UUID tierId, String tier, List<Row> rows) {
    }

    public record Row(String label, List<Seat> seats) {
    }

    /** The ID is the opaque seat ID that Booking stores; the number is a display label. */
    public record Seat(UUID id, int number) {
    }
}
