package com.ticketflow.booking.holds;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/** Plain SQL to set up and inspect seats, so a test does not depend on the code it tests. */
final class HoldFixtures {

    private HoldFixtures() {
    }

    /** An event on sale with this many free seats (5,000 each, currency UZS); returns the seat IDs. */
    static List<UUID> eventWithSeats(JdbcTemplate jdbc, UUID eventId, int seats) {
        jdbc.update("INSERT INTO event_inventory (event_id, layout_version, currency, total_seats) "
                + "VALUES (?, 1, 'UZS', ?)", eventId, seats);
        List<UUID> ids = new ArrayList<>();
        for (int i = 1; i <= seats; i++) {
            UUID seat = UUID.randomUUID();
            ids.add(seat);
            jdbc.update("INSERT INTO event_seats (event_id, seat_id, section_name, row_label, seat_number, tier_name, "
                    + "price_minor) VALUES (?, ?, 'Hall', 'A', ?, 'Standard', 5000)", eventId, seat, i);
        }
        return ids;
    }

    static void sell(JdbcTemplate jdbc, UUID eventId, UUID seat) {
        jdbc.update("UPDATE event_seats SET state = 'SOLD', booking_id = ?, held_until = NULL "
                + "WHERE event_id = ? AND seat_id = ?", UUID.randomUUID(), eventId, seat);
    }

    /** Marks a seat HELD by some other booking until now plus this interval (a negative one makes it expired). */
    static UUID holdBySql(JdbcTemplate jdbc, UUID eventId, UUID seat, String interval) {
        UUID booking = UUID.randomUUID();
        jdbc.update("UPDATE event_seats SET state = 'HELD', booking_id = ?, held_until = now() + ?::interval "
                + "WHERE event_id = ? AND seat_id = ?", booking, interval, eventId, seat);
        return booking;
    }

    static String stateOf(JdbcTemplate jdbc, UUID eventId, UUID seat) {
        return jdbc.queryForObject("SELECT state FROM event_seats WHERE event_id = ? AND seat_id = ?",
                String.class, eventId, seat);
    }

    static UUID bookingOf(JdbcTemplate jdbc, UUID eventId, UUID seat) {
        return jdbc.queryForObject("SELECT booking_id FROM event_seats WHERE event_id = ? AND seat_id = ?",
                UUID.class, eventId, seat);
    }

    static int bookingCount(JdbcTemplate jdbc, UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM bookings WHERE event_id = ?", Integer.class, eventId);
    }
}
