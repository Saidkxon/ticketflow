package com.ticketflow.booking.provisioning;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * A layout in the shape Catalog serves (contracts/catalog.openapi.yaml, PublishedLayout), with 10 seats:
 * section "Stalls" (tier VIP, 15000) with rows A and B of 3 seats, and section "Balcony" (tier Standard, 5000) with
 * row A of 4 seats. The fields can be spoiled to test validation.
 */
final class LayoutFixtures {

    static final int SEATS = 10;
    static final long VIP_PRICE = 15000;
    static final long STANDARD_PRICE = 5000;

    final UUID eventId;
    final List<UUID> seatIds = new ArrayList<>();
    long layoutVersion = 1;
    String currency = "UZS";
    int totalSeats = SEATS;
    boolean unknownTier;
    boolean duplicateSeat;
    UUID layoutEventId;

    LayoutFixtures(UUID eventId) {
        this.eventId = eventId;
        this.layoutEventId = eventId;
        for (int i = 0; i < SEATS; i++) {
            seatIds.add(UUID.randomUUID());
        }
    }

    String json() {
        UUID vip = UUID.randomUUID();
        UUID standard = UUID.randomUUID();
        List<UUID> ids = new ArrayList<>(seatIds);
        if (duplicateSeat) {
            ids.set(SEATS - 1, ids.get(0));
        }
        UUID balconyTier = unknownTier ? UUID.randomUUID() : standard;
        return """
                {"eventId":"%s","layoutVersion":%d,"currency":"%s",
                "priceTiers":[{"id":"%s","name":"VIP","priceMinor":%d},{"id":"%s","name":"Standard","priceMinor":%d}],
                "sections":[
                {"id":"%s","name":"Stalls","tierId":"%s","tier":"VIP","rows":[
                  {"label":"A","seats":%s},{"label":"B","seats":%s}]},
                {"id":"%s","name":"Balcony","tierId":"%s","tier":"Standard","rows":[
                  {"label":"A","seats":%s}]}],
                "totalSeats":%d,"somethingNew":"ignored"}
                """.formatted(layoutEventId, layoutVersion, currency,
                vip, VIP_PRICE, standard, STANDARD_PRICE,
                UUID.randomUUID(), vip, seats(ids.subList(0, 3), 1), seats(ids.subList(3, 6), 1),
                UUID.randomUUID(), balconyTier, seats(ids.subList(6, 10), 1), totalSeats);
    }

    private static String seats(List<UUID> ids, int firstNumber) {
        StringBuilder out = new StringBuilder("[");
        for (int i = 0; i < ids.size(); i++) {
            out.append(i > 0 ? "," : "").append("{\"id\":\"").append(ids.get(i)).append("\",\"number\":")
                    .append(firstNumber + i).append('}');
        }
        return out.append(']').toString();
    }
}
