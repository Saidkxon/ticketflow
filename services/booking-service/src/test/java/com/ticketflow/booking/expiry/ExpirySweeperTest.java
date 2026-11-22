package com.ticketflow.booking.expiry;

import static org.assertj.core.api.Assertions.assertThat;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import com.ticketflow.booking.outbox.OutboxRepository;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * The expiry sweeper against a real PostgreSQL (ADR-0012, part B; ADR-0006). Bookings are set up with plain SQL, so
 * the tests do not depend on the code that takes holds.
 */
class ExpirySweeperTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ExpirySweeper sweeper;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private OutboxRepository outbox;

    @BeforeEach
    void noOtherExpiredBookings() {
        // Other test classes leave expired holds behind; the sweeper would meet them first and the counts would be off.
        jdbc.update("UPDATE bookings SET status = 'EXPIRED' WHERE status = 'HELD' AND expires_at <= now()");
    }

    // ---- helpers

    private List<UUID> eventWithSeats(UUID eventId, int seats) {
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

    /** A HELD booking of these seats whose deadline is now plus this interval (a negative one: already passed). */
    private UUID heldBooking(UUID event, UUID user, List<UUID> seats, String deadlineIn) {
        UUID booking = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (booking_id, event_id, user_id, status, currency, total_minor, expires_at) "
                + "VALUES (?, ?, ?, 'HELD', 'UZS', ?, now() + ?::interval)", booking, event, user, 5000L * seats.size(),
                deadlineIn);
        for (UUID seat : seats) {
            jdbc.update("INSERT INTO booking_seats (booking_id, event_id, seat_id, price_minor) VALUES (?, ?, ?, 5000)",
                    booking, event, seat);
            jdbc.update("UPDATE event_seats SET state = 'HELD', booking_id = ?, held_until = now() + ?::interval "
                    + "WHERE event_id = ? AND seat_id = ?", booking, deadlineIn, event, seat);
        }
        return booking;
    }

    private String statusOf(UUID booking) {
        return jdbc.queryForObject("SELECT status FROM bookings WHERE booking_id = ?", String.class, booking);
    }

    private String seatState(UUID event, UUID seat) {
        return jdbc.queryForObject("SELECT state FROM event_seats WHERE event_id = ? AND seat_id = ?", String.class,
                event, seat);
    }

    private UUID seatOwner(UUID event, UUID seat) {
        return jdbc.queryForObject("SELECT booking_id FROM event_seats WHERE event_id = ? AND seat_id = ?", UUID.class,
                event, seat);
    }

    private List<Map<String, Object>> outboxRows(UUID booking) {
        return jdbc.queryForList("SELECT id, aggregate_type, event_type, CAST(payload AS text) AS payload, "
                + "published_at FROM outbox_events WHERE aggregate_id = ?", booking);
    }

    // ---- what the sweeper does

    @Test
    void anExpiredBookingBecomesExpiredAndItsSeatsAreFreeAgain() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 4);
        UUID booking = heldBooking(event, UUID.randomUUID(), seats.subList(0, 2), "-1 minute");

        ExpirySweeper.Result result = sweeper.sweep();

        assertThat(result.expiredBookings()).isEqualTo(1);
        assertThat(statusOf(booking)).isEqualTo("EXPIRED");
        for (UUID seat : seats.subList(0, 2)) {
            assertThat(seatState(event, seat)).isEqualTo("AVAILABLE");
            assertThat(seatOwner(event, seat)).isNull();
        }
        assertThat(seatState(event, seats.get(2))).isEqualTo("AVAILABLE");
        // What the booking contained is remembered.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seats WHERE booking_id = ?", Integer.class,
                booking)).isEqualTo(2);
    }

    @Test
    void theBookingExpiredEventIsWrittenToTheOutboxWithTheEnvelope() {
        UUID event = UUID.randomUUID();
        UUID user = UUID.randomUUID();
        UUID booking = heldBooking(event, user, eventWithSeats(event, 2), "-1 minute");

        sweeper.sweep();

        List<Map<String, Object>> rows = outboxRows(booking);
        assertThat(rows).hasSize(1);
        Map<String, Object> row = rows.get(0);
        assertThat(row.get("aggregate_type")).isEqualTo("Booking");
        assertThat(row.get("event_type")).isEqualTo("BookingExpired");
        assertThat(row.get("published_at")).isNull();
        String payload = (String) row.get("payload");
        assertThat(JsonPath.<String>read(payload, "$.eventId")).isEqualTo(row.get("id").toString());
        assertThat(JsonPath.<String>read(payload, "$.eventType")).isEqualTo("BookingExpired");
        assertThat(JsonPath.<Integer>read(payload, "$.eventVersion")).isEqualTo(1);
        assertThat(JsonPath.<String>read(payload, "$.producer")).isEqualTo("booking-service");
        assertThat(JsonPath.<String>read(payload, "$.aggregateId")).isEqualTo(booking.toString());
        assertThat(JsonPath.<String>read(payload, "$.data.bookingId")).isEqualTo(booking.toString());
        assertThat(JsonPath.<String>read(payload, "$.data.eventId")).isEqualTo(event.toString());
        assertThat(JsonPath.<String>read(payload, "$.data.userId")).isEqualTo(user.toString());
        assertThat(JsonPath.<String>read(payload, "$.data.expiredAt")).isNotBlank();
    }

    @Test
    void aBookingThatHasNotExpiredIsLeftAlone() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 2);
        UUID booking = heldBooking(event, UUID.randomUUID(), seats, "5 minutes");

        ExpirySweeper.Result result = sweeper.sweep();

        assertThat(result.expiredBookings()).isZero();
        assertThat(statusOf(booking)).isEqualTo("HELD");
        assertThat(seatState(event, seats.get(0))).isEqualTo("HELD");
        assertThat(seatOwner(event, seats.get(0))).isEqualTo(booking);
        assertThat(outboxRows(booking)).isEmpty();
    }

    @Test
    void aSeatThatSomeoneElseTookAfterTheHoldRanOutStaysTheirs() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 3);
        UUID old = heldBooking(event, UUID.randomUUID(), seats.subList(0, 2), "-1 minute");
        // The first seat was taken over by a new hold (lazy expiry) before the sweeper came.
        UUID newer = heldBooking(event, UUID.randomUUID(), seats.subList(0, 1), "10 minutes");

        sweeper.sweep();

        assertThat(statusOf(old)).isEqualTo("EXPIRED");
        assertThat(statusOf(newer)).isEqualTo("HELD");
        assertThat(seatState(event, seats.get(0))).isEqualTo("HELD");
        assertThat(seatOwner(event, seats.get(0))).isEqualTo(newer);
        assertThat(seatState(event, seats.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    void runningAgainDoesNothingMoreAndWritesNoSecondEvent() {
        UUID event = UUID.randomUUID();
        UUID booking = heldBooking(event, UUID.randomUUID(), eventWithSeats(event, 2), "-1 minute");

        assertThat(sweeper.sweep().expiredBookings()).isEqualTo(1);
        assertThat(sweeper.sweep().expiredBookings()).isZero();

        assertThat(outboxRows(booking)).hasSize(1);
    }

    @Test
    void oneRunExpiresAtMostABatchAndTheNextRunContinues() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 7);
        List<UUID> bookings = new ArrayList<>();
        for (UUID seat : seats) {
            bookings.add(heldBooking(event, UUID.randomUUID(), List.of(seat), "-1 minute"));
        }
        ExpirySweeper small = new ExpirySweeper(jdbc, transactions, outbox,
                new ExpiryProperties(false, Duration.ofSeconds(10), 3, Duration.ofSeconds(2), Duration.ofHours(24)));

        assertThat(small.sweep().expiredBookings()).isEqualTo(3);
        assertThat(small.sweep().expiredBookings()).isEqualTo(3);
        assertThat(small.sweep().expiredBookings()).isEqualTo(1);
        assertThat(small.sweep().expiredBookings()).isZero();
        assertThat(bookings).allMatch(b -> statusOf(b).equals("EXPIRED"));
    }

    // ---- several sweepers at once (several Booking copies)

    @Test
    void fourSweepersAtTheSameTimeExpireEveryBookingExactlyOnce() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(event, 20);
        List<UUID> bookings = new ArrayList<>();
        for (UUID seat : seats) {
            bookings.add(heldBooking(event, UUID.randomUUID(), List.of(seat), "-1 minute"));
        }
        ExecutorService pool = Executors.newFixedThreadPool(4);
        try {
            CountDownLatch ready = new CountDownLatch(4);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<ExpirySweeper.Result>> futures = new ArrayList<>();
            for (int i = 0; i < 4; i++) {
                Callable<ExpirySweeper.Result> run = () -> {
                    ready.countDown();
                    go.await();
                    return sweeper.sweep();
                };
                futures.add(pool.submit(run));
            }
            ready.await();
            go.countDown();
            int total = 0;
            for (Future<ExpirySweeper.Result> future : futures) {
                total += future.get(60, TimeUnit.SECONDS).expiredBookings();
            }
            assertThat(total).isEqualTo(20);
        } finally {
            pool.shutdownNow();
        }
        for (UUID booking : bookings) {
            assertThat(statusOf(booking)).isEqualTo("EXPIRED");
            assertThat(outboxRows(booking)).hasSize(1);
        }
        for (UUID seat : seats) {
            assertThat(seatState(event, seat)).isEqualTo("AVAILABLE");
        }
    }

    // ---- idempotency keys (ADR-0009, part C)

    @Test
    void idempotencyKeysOlderThanTheRetentionAreRemoved() {
        UUID user = UUID.randomUUID();
        UUID old = UUID.randomUUID();
        UUID recent = UUID.randomUUID();
        jdbc.update("INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, created_at) "
                + "VALUES (?, ?, 'x', now() - interval '25 hours')", user, old);
        jdbc.update("INSERT INTO idempotency_keys (user_id, idempotency_key, request_hash, created_at) "
                + "VALUES (?, ?, 'x', now() - interval '23 hours')", user, recent);

        ExpirySweeper.Result result = sweeper.sweep();

        assertThat(result.removedIdempotencyKeys()).isGreaterThanOrEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, user, old)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE user_id = ? AND idempotency_key = ?",
                Integer.class, user, recent)).isEqualTo(1);
    }
}
