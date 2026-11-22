package com.ticketflow.booking.holds;

import com.ticketflow.booking.availability.SeatAvailabilityRepository;
import com.ticketflow.booking.web.ApiException;
import com.ticketflow.booking.web.FieldProblem;
import com.ticketflow.booking.web.InvalidRequestException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.PessimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes holds on seats (ADR-0012, part B). A hold is one database transaction that either takes every requested seat
 * or changes nothing.
 *
 * <p>Inside the transaction, in this order:
 * <ol>
 *   <li>the idempotency key is written first, so a retry of the same request waits for the first one and then gets
 *       its booking (ADR-0009, part C);</li>
 *   <li>one lock per user and event, so a customer cannot take two holds at once by sending two requests;</li>
 *   <li>the seat rows are locked <b>in order of seat ID</b>, whatever order the caller listed them in, so two
 *       requests for overlapping seats cannot lock each other (no deadlock);</li>
 *   <li>a seat is taken when it is free by the one rule of {@link SeatAvailabilityRepository#UNAVAILABLE}: a hold
 *       whose deadline has passed counts as free whether or not any cleanup job has run;</li>
 *   <li>the deadline is database time plus the hold duration. No application clock is used.</li>
 * </ol>
 *
 * <p>Waiting for a seat that another request is changing is limited by a lock timeout, so heavy contention gives a
 * quick "try again" instead of a growing queue.
 */
@Component
class HoldService {

    private static final Logger log = LoggerFactory.getLogger(HoldService.class);

    private static final String ON_SALE = "SELECT currency FROM event_inventory WHERE event_id = ?";
    private static final String NEW_KEY =
            "INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash) VALUES (?, ?, ?) "
                    + "ON CONFLICT DO NOTHING";
    private static final String KEY_ROW =
            "SELECT request_hash, booking_id FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?";
    private static final String USER_EVENT_LOCK = "SELECT pg_advisory_xact_lock(hashtextextended(?::text, 0))";
    private static final String ACTIVE_HOLD =
            "SELECT EXISTS (SELECT 1 FROM bookings WHERE event_id = ? AND user_id = ? AND status = 'HELD' "
                    + "AND expires_at > now())";
    /** Locks the rows in seat-ID order (the lock node of the plan sits above the sort) and says which are taken. */
    private static final String LOCK_SEATS =
            "SELECT s.seat_id, s.price_minor, " + SeatAvailabilityRepository.UNAVAILABLE + " AS unavailable "
                    + "FROM event_seats s WHERE s.event_id = ? AND s.seat_id IN (%s) ORDER BY s.seat_id FOR UPDATE";
    private static final String INSERT_BOOKING =
            "INSERT INTO bookings (booking_id, event_id, user_id, status, currency, total_minor, expires_at) "
                    + "VALUES (?, ?, ?, 'HELD', ?, ?, clock_timestamp() + ?::double precision * interval '1 second') "
                    + "RETURNING expires_at";
    private static final String HOLD_SEATS =
            "UPDATE event_seats SET state = 'HELD', booking_id = ?, held_until = ? "
                    + "WHERE event_id = ? AND seat_id IN (%s)";
    private static final String INSERT_BOOKING_SEAT =
            "INSERT INTO booking_seats (booking_id, event_id, seat_id, price_minor) VALUES (?, ?, ?, ?)";
    private static final String LINK_KEY =
            "UPDATE idempotency_keys SET booking_id = ? WHERE user_id = ? AND idempotency_key = ?";

    private record LockedSeat(UUID seatId, long priceMinor, boolean unavailable) {
    }

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;
    private final BookingReader reader;
    private final HoldProperties properties;

    HoldService(JdbcTemplate jdbc, PlatformTransactionManager transactions, BookingReader reader,
            HoldProperties properties) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
        this.reader = reader;
        this.properties = properties;
    }

    /**
     * Holds the seats for the user, or gives back the booking this request already made.
     *
     * @throws InvalidRequestException when the seat list is empty, too long, has a repeat, or names a seat that is
     *                                 not a seat of this event
     * @throws ApiException            with a stable code for every refusal the caller can act on
     */
    BookingResponse hold(UUID userId, UUID idempotencyKey, UUID eventId, List<UUID> requestedSeats) {
        List<UUID> seats = checked(requestedSeats);
        String fingerprint = fingerprint(eventId, seats);
        try {
            return transaction.execute(status -> take(userId, idempotencyKey, eventId, seats, fingerprint));
        } catch (RuntimeException e) {
            if (isLockFailure(e)) {
                log.warn("hold for event {} gave up waiting for a seat lock", eventId);
                throw ApiException.seatsBusy();
            }
            throw e;
        }
    }

    private BookingResponse take(UUID userId, UUID key, UUID eventId, List<UUID> seats, String fingerprint) {
        if (jdbc.queryForList(ON_SALE, String.class, eventId).isEmpty()) {
            throw ApiException.notOnSale();
        }
        // SET LOCAL ends with this transaction. The number is milliseconds and comes from our own settings.
        jdbc.execute("SET LOCAL lock_timeout = " + properties.lockTimeout().toMillis());

        if (jdbc.update(NEW_KEY, userId, key, fingerprint) == 0) {
            return replay(userId, key, fingerprint);
        }
        // pg_advisory_xact_lock returns void; only its effect matters, so no column is read.
        jdbc.query(USER_EVENT_LOCK, (RowCallbackHandler) rs -> { }, userId + ":" + eventId);
        if (Boolean.TRUE.equals(jdbc.queryForObject(ACTIVE_HOLD, Boolean.class, eventId, userId))) {
            throw ApiException.activeHoldExists();
        }

        List<LockedSeat> locked = lockSeats(eventId, seats);
        if (locked.size() != seats.size()) {
            throw new InvalidRequestException(List.of(
                    new FieldProblem("seatIds", "one or more seats do not belong to this event")));
        }
        List<UUID> unavailable = locked.stream().filter(LockedSeat::unavailable).map(LockedSeat::seatId).toList();
        if (!unavailable.isEmpty()) {
            throw ApiException.seatsUnavailable(unavailable);
        }

        UUID bookingId = UUID.randomUUID();
        String currency = jdbc.queryForObject(ON_SALE, String.class, eventId);
        long total = locked.stream().mapToLong(LockedSeat::priceMinor).sum();
        OffsetDateTime expiresAt = jdbc.queryForObject(INSERT_BOOKING, OffsetDateTime.class, bookingId, eventId,
                userId, currency, total, properties.duration().toMillis() / 1000.0);

        List<Object> holdArgs = new ArrayList<>();
        holdArgs.add(bookingId);
        holdArgs.add(expiresAt);
        holdArgs.add(eventId);
        holdArgs.addAll(seats);
        int held = jdbc.update(HOLD_SEATS.formatted(placeholders(seats.size())), holdArgs.toArray());
        if (held != seats.size()) {
            // The rows are locked, so this cannot happen; if it ever does, nothing is kept.
            throw new IllegalStateException("held " + held + " of " + seats.size() + " locked seats");
        }
        List<Object[]> lines = locked.stream()
                .map(s -> new Object[] {bookingId, eventId, s.seatId(), s.priceMinor()}).toList();
        jdbc.batchUpdate(INSERT_BOOKING_SEAT, lines);
        jdbc.update(LINK_KEY, bookingId, userId, key);

        log.info("booking {} holds {} seats of event {}", bookingId, seats.size(), eventId);
        return reader.find(bookingId).orElseThrow();
    }

    /** The key was used before: the same request gets its booking back, a different one is refused (ADR-0009). */
    private BookingResponse replay(UUID userId, UUID key, String fingerprint) {
        record Stored(String hash, UUID bookingId) {
        }
        Stored stored = jdbc.query(KEY_ROW, rs -> rs.next()
                ? new Stored(rs.getString(1), rs.getObject(2, UUID.class)) : null, userId, key);
        if (stored == null || stored.bookingId() == null) {
            throw ApiException.requestInProgress();
        }
        if (!stored.hash().equals(fingerprint)) {
            throw ApiException.idempotencyKeyReused();
        }
        return reader.find(stored.bookingId()).orElseThrow();
    }

    private List<LockedSeat> lockSeats(UUID eventId, List<UUID> seats) {
        List<Object> args = new ArrayList<>();
        args.add(eventId);
        args.addAll(seats);
        return jdbc.query(LOCK_SEATS.formatted(placeholders(seats.size())),
                (rs, row) -> new LockedSeat(rs.getObject(1, UUID.class), rs.getLong(2), rs.getBoolean(3)),
                args.toArray());
    }

    /** The seat list, sorted, after the checks that need no database. */
    private List<UUID> checked(List<UUID> requested) {
        if (requested == null || requested.isEmpty()) {
            throw invalid("seatIds", "must contain at least one seat");
        }
        if (requested.size() > properties.maxSeats()) {
            throw invalid("seatIds", "must contain at most " + properties.maxSeats() + " seats");
        }
        if (requested.stream().anyMatch(java.util.Objects::isNull)) {
            throw invalid("seatIds", "must not contain empty entries");
        }
        List<UUID> sorted = new ArrayList<>(requested);
        sorted.sort(Comparator.comparing(UUID::toString));
        for (int i = 1; i < sorted.size(); i++) {
            if (sorted.get(i).equals(sorted.get(i - 1))) {
                throw invalid("seatIds", "must not contain the same seat twice");
            }
        }
        return List.copyOf(sorted);
    }

    private static InvalidRequestException invalid(String field, String problem) {
        return new InvalidRequestException(List.of(new FieldProblem(field, problem)));
    }

    private static String placeholders(int count) {
        return String.join(", ", Collections.nCopies(count, "?"));
    }

    /** What makes two requests "the same" for an idempotency key: the event and the set of seats, in any order. */
    static String fingerprint(UUID eventId, List<UUID> sortedSeats) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(("hold:" + eventId).getBytes(StandardCharsets.UTF_8));
            for (UUID seat : sortedSeats) {
                digest.update((byte) '\n');
                digest.update(seat.toString().getBytes(StandardCharsets.UTF_8));
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    /** A lock wait that timed out (55P03) or a deadlock (40P01): both mean "try again", wherever Spring wraps them. */
    static boolean isLockFailure(Throwable error) {
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
