package com.ticketflow.booking.provisioning;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;
import java.util.UUID;

/**
 * The frozen layout that Catalog serves at GET /api/v1/events/{eventId}/layout (contracts/catalog.openapi.yaml,
 * PublishedLayout). Only the fields Booking needs are listed; others are ignored.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
record CatalogLayout(UUID eventId, Long layoutVersion, String currency, List<PriceTier> priceTiers,
        List<Section> sections, Integer totalSeats) {

    @JsonIgnoreProperties(ignoreUnknown = true)
    record PriceTier(UUID id, String name, Long priceMinor) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Section(UUID id, String name, UUID tierId, List<Row> rows) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Row(String label, List<Seat> seats) {
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    record Seat(UUID id, Integer number) {
    }
}
