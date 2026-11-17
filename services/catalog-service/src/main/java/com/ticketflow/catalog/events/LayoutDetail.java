package com.ticketflow.catalog.events;

import java.util.List;
import java.util.UUID;

/**
 * The layout of an event as stored: the tiers, and the sections with the number of seats they produced.
 * The currency is null until a layout has been saved. Field names follow contracts/catalog.openapi.yaml.
 */
public record LayoutDetail(String currency, List<PriceTierView> priceTiers, List<SectionView> sections,
        int totalSeats) {

    public record PriceTierView(UUID id, String name, long priceMinor) {
    }

    /** {@code tier} is the name of the section's price tier. */
    public record SectionView(UUID id, String name, String tier, int rows, int seatsPerRow, int seatCount) {
    }
}
