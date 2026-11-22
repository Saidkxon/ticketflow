package com.ticketflow.booking.holds;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Loads a booking with its seats. The one place that turns booking rows into a {@link BookingResponse}. */
@Repository
class BookingReader {

    private static final String BOOKING =
            "SELECT booking_id, event_id, status, currency, total_minor, expires_at, clock_timestamp() "
                    + "FROM bookings WHERE booking_id = ?";
    private static final String SEATS =
            "SELECT bs.seat_id, s.section_name, s.row_label, s.seat_number, s.tier_name, bs.price_minor "
                    + "FROM booking_seats bs JOIN event_seats s ON s.event_id = bs.event_id AND s.seat_id = bs.seat_id "
                    + "WHERE bs.booking_id = ? ORDER BY s.section_name, s.row_label, s.seat_number";

    private final JdbcTemplate jdbc;

    BookingReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    Optional<BookingResponse> find(UUID bookingId) {
        List<BookingResponse> found = jdbc.query(BOOKING, (rs, row) -> new BookingResponse(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getString(4),
                rs.getLong(5),
                instant(rs.getObject(6, OffsetDateTime.class)),
                instant(rs.getObject(7, OffsetDateTime.class)),
                List.of()), bookingId);
        if (found.isEmpty()) {
            return Optional.empty();
        }
        List<BookingResponse.Seat> seats = jdbc.query(SEATS, (rs, row) -> new BookingResponse.Seat(
                rs.getObject(1, UUID.class), rs.getString(2), rs.getString(3), rs.getInt(4), rs.getString(5),
                rs.getLong(6)), bookingId);
        BookingResponse b = found.get(0);
        return Optional.of(new BookingResponse(b.id(), b.eventId(), b.status(), b.currency(), b.totalMinor(),
                b.expiresAt(), b.serverTime(), List.copyOf(seats)));
    }

    private static Instant instant(OffsetDateTime time) {
        return time.toInstant();
    }
}
