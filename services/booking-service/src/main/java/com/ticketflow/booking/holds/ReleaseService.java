package com.ticketflow.booking.holds;

import com.ticketflow.booking.web.ApiException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Lets a customer give up a hold (ADR-0006: HELD to CANCELLED, "customer abandons"). One transaction: the booking
 * becomes CANCELLED and its seats become AVAILABLE, so the seat map shows them free at once.
 *
 * <ul>
 *   <li><b>Same lock order as the sweeper.</b> The booking row first, then its seats in order of seat ID. A hold
 *       request locks seats in the same order, so none of them can deadlock with a release.</li>
 *   <li><b>Release against the sweeper.</b> The sweeper takes the booking row with SKIP LOCKED; a release waits for
 *       it (at most the lock timeout). Whoever is second sees the new status: a release after the sweeper is a 409, a
 *       sweeper after a release finds a CANCELLED booking and leaves it alone.</li>
 *   <li><b>Repeating is safe.</b> Releasing a booking that is already CANCELLED answers the same booking again, so a
 *       retry after a lost answer does no harm.</li>
 *   <li><b>No event.</b> Nobody needs to react to a customer abandoning their own hold: no money has moved. The
 *       {@code BookingCancelled} event of ADR-0006 is for a confirmed booking and comes with the payment slice.</li>
 * </ul>
 */
@Service
class ReleaseService {

    private static final Logger log = LoggerFactory.getLogger(ReleaseService.class);

    /** The customer's own booking, locked; the last column says whether its deadline has passed. */
    private static final String CLAIM =
            "SELECT status, event_id, expires_at <= clock_timestamp() FROM bookings "
                    + "WHERE booking_id = ? AND user_id = ? FOR UPDATE";
    private static final String LOCK_SEATS =
            "SELECT seat_id FROM event_seats WHERE event_id = ? AND booking_id = ? AND state = 'HELD' "
                    + "ORDER BY seat_id FOR UPDATE";
    private static final String RELEASE_SEATS =
            "UPDATE event_seats SET state = 'AVAILABLE', booking_id = NULL, held_until = NULL "
                    + "WHERE event_id = ? AND booking_id = ? AND state = 'HELD'";
    private static final String CANCEL = "UPDATE bookings SET status = 'CANCELLED' WHERE booking_id = ?";

    private record Claimed(String status, UUID eventId, boolean deadlinePassed) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BookingReader reader;
    private final HoldProperties properties;

    ReleaseService(JdbcTemplate jdbc, PlatformTransactionManager transactions, BookingReader reader,
            HoldProperties properties) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.reader = reader;
        this.properties = properties;
    }

    BookingResponse release(UUID userId, UUID bookingId) {
        try {
            return transaction.execute(status -> releaseInTransaction(userId, bookingId));
        } catch (RuntimeException e) {
            if (HoldService.isLockFailure(e)) {
                throw ApiException.seatsBusy();
            }
            throw e;
        }
    }

    private BookingResponse releaseInTransaction(UUID userId, UUID bookingId) {
        // SET LOCAL ends with this transaction. The number is milliseconds and comes from our own settings.
        jdbc.execute("SET LOCAL lock_timeout = " + properties.lockTimeout().toMillis());
        List<Claimed> claimed = jdbc.query(CLAIM, (rs, row) -> new Claimed(rs.getString(1),
                rs.getObject(2, UUID.class), rs.getBoolean(3)), bookingId, userId);
        if (claimed.isEmpty()) {
            throw ApiException.bookingNotFound();
        }
        Claimed booking = claimed.get(0);
        if ("CANCELLED".equals(booking.status())) {
            return answer(bookingId, userId);
        }
        if (!"HELD".equals(booking.status()) || booking.deadlinePassed()) {
            throw ApiException.bookingNotActive();
        }
        jdbc.queryForList(LOCK_SEATS, UUID.class, booking.eventId(), bookingId);
        int released = jdbc.update(RELEASE_SEATS, booking.eventId(), bookingId);
        jdbc.update(CANCEL, bookingId);
        log.info("booking {} released by its customer; {} seats are free again", bookingId, released);
        return answer(bookingId, userId);
    }

    private BookingResponse answer(UUID bookingId, UUID userId) {
        return reader.findOwned(bookingId, userId).orElseThrow(ApiException::bookingNotFound);
    }
}
