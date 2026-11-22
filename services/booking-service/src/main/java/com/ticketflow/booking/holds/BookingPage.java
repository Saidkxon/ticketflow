package com.ticketflow.booking.holds;

import java.util.List;

/** One page of a customer's bookings, newest first (ADR-0009, part D). Same shape as the event list of Catalog. */
public record BookingPage(List<BookingResponse> items, int page, int size, long totalItems) {
}
