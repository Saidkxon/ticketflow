package com.ticketflow.booking.holds;

import java.util.List;
import java.util.UUID;

/** The body of {@code POST /api/v1/bookings}: which seats of which event the caller wants to hold. */
public record HoldRequest(UUID eventId, List<UUID> seatIds) {
}
