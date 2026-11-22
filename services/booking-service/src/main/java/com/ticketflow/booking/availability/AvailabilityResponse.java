package com.ticketflow.booking.availability;

import java.util.List;
import java.util.UUID;

/** The body of the availability answer: the seats that cannot be taken right now (contracts/booking.openapi.yaml). */
public record AvailabilityResponse(UUID eventId, List<UUID> unavailableSeatIds) {
}
