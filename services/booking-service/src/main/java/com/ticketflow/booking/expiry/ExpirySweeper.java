package com.ticketflow.booking.expiry;

import com.ticketflow.booking.outbox.OutboxRepository;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Expires bookings whose hold deadline has passed (ADR-0012, part B; ADR-0006): the booking becomes EXPIRED, its seats
 * become AVAILABLE again, and a {@code BookingExpired} event is written to the outbox, all in one transaction.
 *
 * <p>This is housekeeping, not the rule. A HELD seat past its deadline is already free for everybody (lazy expiry,
 * {@code SeatAvailabilityRepository.UNAVAILABLE}), so nothing waits for the sweeper; it exists to tidy the data and
 * to tell the customer.
 *
 * <ul>
 *   <li><b>One booking per transaction.</b> A hold request locks its seats in order of seat ID. If the sweeper locked
 *       the seats of several bookings in one transaction, in the order of the bookings, it could wait for a seat that a
 *       hold request has while that request waits for a seat the sweeper has, a deadlock. With one booking per
 *       transaction the sweeper locks the seats of a single event in order of seat ID, like every hold request.</li>
 *   <li><b>Several copies are safe.</b> The booking row is taken with {@code FOR UPDATE SKIP LOCKED}, so two sweepers
 *       never expire the same booking, and a second run finds nothing to do (idempotent).</li>
 *   <li><b>Only the booking's own seats.</b> A seat is released only while it is still held by this booking. If its
 *       hold expired and somebody else took the seat (lazy expiry), it belongs to them and stays theirs.</li>
 * </ul>
 */
@Component
public class ExpirySweeper {

    /** What one run did. */
    public record Result(int expiredBookings, int removedIdempotencyKeys) {
    }

    private record Claimed(UUID bookingId, UUID eventId, UUID userId, Instant deadline, Instant now) {
    }

    private static final Logger log = LoggerFactory.getLogger(ExpirySweeper.class);

    /** The oldest expired HELD booking that nobody else is working on. */
    private static final String CLAIM =
            "SELECT booking_id, event_id, user_id, expires_at, clock_timestamp() FROM bookings "
                    + "WHERE status = 'HELD' AND expires_at <= now() ORDER BY expires_at, booking_id LIMIT 1 "
                    + "FOR UPDATE SKIP LOCKED";
    /** Locks the booking's seats in seat-ID order, like a hold request does. A seat taken by someone else is not listed. */
    private static final String LOCK_SEATS =
            "SELECT seat_id FROM event_seats WHERE event_id = ? AND booking_id = ? AND state = 'HELD' "
                    + "AND held_until <= now() ORDER BY seat_id FOR UPDATE";
    private static final String RELEASE_SEATS =
            "UPDATE event_seats SET state = 'AVAILABLE', booking_id = NULL, held_until = NULL "
                    + "WHERE event_id = ? AND booking_id = ? AND state = 'HELD' AND held_until <= now()";
    private static final String EXPIRE_BOOKING = "UPDATE bookings SET status = 'EXPIRED' WHERE booking_id = ?";
    private static final String REMOVE_OLD_KEYS =
            "DELETE FROM idempotency_keys WHERE (user_id, idempotency_key) IN ("
                    + "SELECT user_id, idempotency_key FROM idempotency_keys "
                    + "WHERE created_at < now() - ?::double precision * interval '1 second' LIMIT 1000)";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final OutboxRepository outbox;
    private final ExpiryProperties properties;

    ExpirySweeper(JdbcTemplate jdbc, PlatformTransactionManager transactions, OutboxRepository outbox,
            ExpiryProperties properties) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.outbox = outbox;
        this.properties = properties;
    }

    /**
     * Expires up to {@code batchSize} bookings, then removes idempotency keys older than the retention time. A seat that a
     * hold request is changing for longer than the lock timeout ends the run early; the next run continues.
     */
    public Result sweep() {
        int expired = 0;
        try {
            while (expired < properties.batchSize()) {
                if (!Boolean.TRUE.equals(transaction.execute(status -> expireOne()))) {
                    break;
                }
                expired++;
            }
        } catch (RuntimeException e) {
            if (!isLockFailure(e)) {
                throw e;
            }
            log.info("expiry sweeper stopped early: a seat is busy; the next run continues");
        }
        int keys = removeOldKeys();
        if (expired > 0 || keys > 0) {
            log.info("expiry sweeper expired {} bookings and removed {} old idempotency keys", expired, keys);
        }
        return new Result(expired, keys);
    }

    /** Expires one booking in its own transaction; false when there is none to expire. */
    private boolean expireOne() {
        Claimed claimed = jdbc.query(CLAIM, rs -> rs.next() ? new Claimed(
                rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getObject(3, UUID.class),
                rs.getObject(4, OffsetDateTime.class).toInstant(), rs.getObject(5, OffsetDateTime.class).toInstant())
                : null);
        if (claimed == null) {
            return false;
        }
        // SET LOCAL ends with this transaction. The number is milliseconds and comes from our own settings.
        jdbc.execute("SET LOCAL lock_timeout = " + properties.lockTimeout().toMillis());
        jdbc.queryForList(LOCK_SEATS, UUID.class, claimed.eventId(), claimed.bookingId());
        int released = jdbc.update(RELEASE_SEATS, claimed.eventId(), claimed.bookingId());
        jdbc.update(EXPIRE_BOOKING, claimed.bookingId());

        UUID messageId = UUID.randomUUID();
        // The sweeper is not a request, so the event has no correlation ID.
        outbox.insert(messageId, "Booking", claimed.bookingId(), BookingExpiredMessage.EVENT_TYPE,
                BookingExpiredMessage.json(messageId, claimed.bookingId(), claimed.eventId(), claimed.userId(),
                        claimed.now(), claimed.deadline(), null));
        log.info("booking {} expired; {} seats released", claimed.bookingId(), released);
        return true;
    }

    private int removeOldKeys() {
        Integer removed = transaction.execute(status ->
                jdbc.update(REMOVE_OLD_KEYS, properties.idempotencyKeyRetention().toMillis() / 1000.0));
        return removed == null ? 0 : removed;
    }

    /** A lock wait that timed out (55P03) or a deadlock (40P01), wherever Spring wraps it. */
    private static boolean isLockFailure(Throwable error) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof PessimisticLockingFailureException) {
                return true;
            }
            if (t instanceof SQLException sql && ("55P03".equals(sql.getSQLState()) || "40P01".equals(sql.getSQLState()))) {
                return true;
            }
            if (t.getCause() == t) {
                break;
            }
        }
        return false;
    }
}
