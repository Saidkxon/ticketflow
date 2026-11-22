package com.ticketflow.booking.holds;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Loads bookings with their seats. The one place that turns booking rows into a {@link BookingResponse}.
 *
 * <p>The status is the one a customer must see: a HELD booking whose deadline has passed is shown as EXPIRED even if
 * the sweeper has not got to it yet, the same rule as lazy expiry for the seats (ADR-0012). Without this a customer
 * could read "HELD" for a booking whose seats anybody can already take.
 */
@Repository
class BookingReader {

    private static final String SELECT =
            "SELECT booking_id, event_id, "
                    + "CASE WHEN status = 'HELD' AND expires_at <= clock_timestamp() THEN 'EXPIRED' ELSE status END, "
                    + "currency, total_minor, expires_at, clock_timestamp() FROM bookings ";
    private static final String ONE = SELECT + "WHERE booking_id = ?";
    private static final String ONE_OF_USER = SELECT + "WHERE booking_id = ? AND user_id = ?";
    private static final String PAGE_OF_USER =
            SELECT + "WHERE user_id = ? ORDER BY created_at DESC, booking_id DESC LIMIT ? OFFSET ?";
    private static final String COUNT_OF_USER = "SELECT count(*) FROM bookings WHERE user_id = ?";
    private static final String SEATS =
            "SELECT bs.booking_id, bs.seat_id, s.section_name, s.row_label, s.seat_number, s.tier_name, bs.price_minor "
                    + "FROM booking_seats bs JOIN event_seats s ON s.event_id = bs.event_id AND s.seat_id = bs.seat_id "
                    + "WHERE bs.booking_id IN (%s) ORDER BY s.section_name, s.row_label, s.seat_number";

    private final JdbcTemplate jdbc;

    BookingReader(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Any booking; for the code that has just made it. A request of a customer uses {@link #findOwned}. */
    Optional<BookingResponse> find(UUID bookingId) {
        return withSeats(jdbc.query(ONE, BookingReader::booking, bookingId)).stream().findFirst();
    }

    /** The booking, only if it belongs to this customer; somebody else's booking looks like one that does not exist. */
    Optional<BookingResponse> findOwned(UUID bookingId, UUID userId) {
        return withSeats(jdbc.query(ONE_OF_USER, BookingReader::booking, bookingId, userId)).stream().findFirst();
    }

    /** One page of the customer's bookings, newest first. */
    List<BookingResponse> pageOf(UUID userId, int size, long offset) {
        return withSeats(jdbc.query(PAGE_OF_USER, BookingReader::booking, userId, size, offset));
    }

    long countOf(UUID userId) {
        Long count = jdbc.queryForObject(COUNT_OF_USER, Long.class, userId);
        return count == null ? 0 : count;
    }

    /** Adds the seats of all bookings with one query, so a page of bookings costs two queries, not one per booking. */
    private List<BookingResponse> withSeats(List<BookingResponse> bookings) {
        if (bookings.isEmpty()) {
            return bookings;
        }
        String placeholders = String.join(",", Collections.nCopies(bookings.size(), "?"));
        Object[] ids = bookings.stream().map(BookingResponse::id).toArray();
        Map<UUID, List<BookingResponse.Seat>> seats = new LinkedHashMap<>();
        jdbc.query(SEATS.formatted(placeholders), rs -> {
            seats.computeIfAbsent(rs.getObject(1, UUID.class), id -> new ArrayList<>()).add(
                    new BookingResponse.Seat(rs.getObject(2, UUID.class), rs.getString(3), rs.getString(4),
                            rs.getInt(5), rs.getString(6), rs.getLong(7)));
        }, ids);
        List<BookingResponse> result = new ArrayList<>();
        for (BookingResponse b : bookings) {
            result.add(new BookingResponse(b.id(), b.eventId(), b.status(), b.currency(), b.totalMinor(),
                    b.expiresAt(), b.serverTime(), List.copyOf(seats.getOrDefault(b.id(), List.of()))));
        }
        return result;
    }

    private static BookingResponse booking(ResultSet rs, int row) throws SQLException {
        return new BookingResponse(
                rs.getObject(1, UUID.class),
                rs.getObject(2, UUID.class),
                rs.getString(3),
                rs.getString(4),
                rs.getLong(5),
                instant(rs.getObject(6, OffsetDateTime.class)),
                instant(rs.getObject(7, OffsetDateTime.class)),
                List.of());
    }

    private static Instant instant(OffsetDateTime time) {
        return time.toInstant();
    }
}
