package com.ticketflow.booking.holds;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A booking as the caller sees it. {@code serverTime} is the database clock at the moment of the answer, so a
 * countdown to {@code expiresAt} does not depend on the clock of the caller's device (FR-15).
 */
public record BookingResponse(
        UUID id,
        UUID eventId,
        String status,
        String currency,
        long totalMinor,
        Instant expiresAt,
        Instant serverTime,
        List<Seat> seats) {

    /** One seat of the booking, with the labels to show and the price it had when it was held. */
    public record Seat(UUID seatId, String section, String row, int number, String tier, long priceMinor) {
    }
}
