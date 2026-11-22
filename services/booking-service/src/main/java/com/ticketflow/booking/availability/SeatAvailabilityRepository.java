package com.ticketflow.booking.availability;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/**
 * Reads seat availability. This class owns the rule that every query about "is this seat taken" must apply
 * (ADR-0012, part B): a seat is unavailable when it is SOLD, or HELD with a deadline that is still in the future by
 * the database clock. A HELD seat whose deadline has passed counts as available, whether or not the sweeper has
 * tidied it up yet, so correctness never depends on a background job. Hold and booking code reuses this rule
 * instead of writing its own copy.
 */
@Repository
public class SeatAvailabilityRepository implements UnavailableSeats {

    /** The condition, written once, for the seat table aliased as {@code s}. */
    public static final String UNAVAILABLE =
            "(s.state = 'SOLD' OR (s.state = 'HELD' AND s.held_until > now()))";

    /**
     * One query for both questions: the event row says that the event is on sale, and the joined seats (none when
     * everything is free, then one row with a null seat ID) are the unavailable ones.
     */
    private static final String SQL =
            "SELECT s.seat_id FROM event_inventory i "
                    + "LEFT JOIN event_seats s ON s.event_id = i.event_id AND " + UNAVAILABLE + " "
                    + "WHERE i.event_id = ? ORDER BY s.seat_id";

    private final JdbcTemplate jdbc;

    SeatAvailabilityRepository(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Optional<List<UUID>> unavailableSeatIds(UUID eventId) {
        return jdbc.query(SQL, rs -> {
            boolean onSale = false;
            List<UUID> ids = new ArrayList<>();
            while (rs.next()) {
                onSale = true;
                UUID id = rs.getObject(1, UUID.class);
                if (id != null) {
                    ids.add(id);
                }
            }
            return onSale ? Optional.of(List.copyOf(ids)) : Optional.<List<UUID>>empty();
        }, eventId);
    }
}
