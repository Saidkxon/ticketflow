package com.ticketflow.booking.holds;

import static com.ticketflow.booking.holds.HoldFixtures.bookingCount;
import static com.ticketflow.booking.holds.HoldFixtures.bookingOf;
import static com.ticketflow.booking.holds.HoldFixtures.eventWithSeats;
import static com.ticketflow.booking.holds.HoldFixtures.holdBySql;
import static com.ticketflow.booking.holds.HoldFixtures.sell;
import static com.ticketflow.booking.holds.HoldFixtures.stateOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import com.ticketflow.booking.web.ApiException;
import com.ticketflow.booking.web.InvalidRequestException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Holds against a real PostgreSQL (ADR-0012, test obligations 1, 2 and 4; NFR-1, NFR-4). The race tests start all
 * callers together with a latch and use the service directly, so they test the database rules and nothing else.
 */
class HoldServiceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private HoldService holds;

    @Autowired
    private JdbcTemplate jdbc;

    @Autowired
    private PlatformTransactionManager transactions;

    /** What one caller got: a booking, or the refusal. Any other kind of failure fails the test. */
    private record Outcome(BookingResponse booking, ApiException refusal) {
        boolean won() {
            return booking != null;
        }
    }

    private static List<Outcome> race(List<Callable<BookingResponse>> calls) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(calls.size());
        try {
            CountDownLatch ready = new CountDownLatch(calls.size());
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (Callable<BookingResponse> call : calls) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome(call.call(), null);
                    } catch (ApiException refusal) {
                        return new Outcome(null, refusal);
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> future : futures) {
                outcomes.add(future.get(60, TimeUnit.SECONDS));
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    private BookingResponse hold(UUID user, UUID event, List<UUID> seats) {
        return holds.hold(user, UUID.randomUUID(), event, seats);
    }

    private static ApiException refusal(Runnable action) {
        try {
            action.run();
        } catch (ApiException e) {
            return e;
        }
        throw new AssertionError("expected a refusal");
    }

    // ---- the hold itself

    @Test
    void aHoldTakesTheSeatsAndAnswersWithWhatTheCustomerNeeds() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 5);
        UUID user = UUID.randomUUID();
        Instant dbNow = jdbc.queryForObject("SELECT now()", java.sql.Timestamp.class).toInstant();

        BookingResponse booking = hold(user, event, List.of(seats.get(2), seats.get(0)));

        assertThat(booking.status()).isEqualTo("HELD");
        assertThat(booking.eventId()).isEqualTo(event);
        assertThat(booking.currency()).isEqualTo("UZS");
        assertThat(booking.totalMinor()).isEqualTo(10_000);
        assertThat(booking.seats()).extracting(BookingResponse.Seat::number).containsExactly(1, 3);
        assertThat(booking.seats()).extracting(BookingResponse.Seat::priceMinor).containsOnly(5_000L);
        // The deadline is database time plus 10 minutes (a little less than that after the time the request took).
        assertThat(booking.expiresAt()).isAfter(dbNow.plusSeconds(9 * 60)).isBefore(dbNow.plusSeconds(10 * 60 + 30));
        assertThat(booking.serverTime()).isBefore(booking.expiresAt());
        for (UUID seat : List.of(seats.get(0), seats.get(2))) {
            assertThat(stateOf(jdbc, event, seat)).isEqualTo("HELD");
            assertThat(bookingOf(jdbc, event, seat)).isEqualTo(booking.id());
        }
        assertThat(stateOf(jdbc, event, seats.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    void theSeatsAreHeldInOneBookingAndTheOthersStayFree() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 4);

        BookingResponse booking = hold(UUID.randomUUID(), event, seats.subList(0, 2));

        assertThat(jdbc.queryForObject("SELECT count(*) FROM booking_seats WHERE booking_id = ?", Integer.class,
                booking.id())).isEqualTo(2);
        assertThat(stateOf(jdbc, event, seats.get(2))).isEqualTo("AVAILABLE");
        assertThat(stateOf(jdbc, event, seats.get(3))).isEqualTo("AVAILABLE");
    }

    // ---- NFR-1, NFR-4: one seat, many users

    @Test
    void fiftyUsersAfterTheSameSeatExactlyOneGetsIt() throws Exception {
        UUID event = UUID.randomUUID();
        UUID seat = eventWithSeats(jdbc, event, 3).get(0);
        List<Callable<BookingResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            UUID user = UUID.randomUUID();
            calls.add(() -> hold(user, event, List.of(seat)));
        }

        List<Outcome> outcomes = race(calls);

        List<Outcome> winners = outcomes.stream().filter(Outcome::won).toList();
        assertThat(winners).hasSize(1);
        outcomes.stream().filter(o -> !o.won()).forEach(o -> {
            assertThat(o.refusal().code()).isEqualTo("SEATS_UNAVAILABLE");
            assertThat(o.refusal().properties().get("unavailableSeatIds")).isEqualTo(List.of(seat));
        });
        assertThat(bookingCount(jdbc, event)).isEqualTo(1);
        assertThat(stateOf(jdbc, event, seat)).isEqualTo("HELD");
        assertThat(bookingOf(jdbc, event, seat)).isEqualTo(winners.get(0).booking().id());
    }

    // ---- test obligation 2: overlapping sets

    @Test
    void overlappingRequestsInOppositeOrderNeitherDeadlockNorLeaveHalfAHold() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID event = UUID.randomUUID();
            List<UUID> seats = eventWithSeats(jdbc, event, 6);
            List<UUID> forward = seats.subList(0, 4);
            List<UUID> backward = new ArrayList<>(forward);
            Collections.reverse(backward);
            UUID a = UUID.randomUUID();
            UUID b = UUID.randomUUID();

            List<Outcome> outcomes = race(List.of(() -> hold(a, event, forward), () -> hold(b, event, backward)));

            assertThat(outcomes.stream().filter(Outcome::won)).hasSize(1);
            outcomes.stream().filter(o -> !o.won())
                    .forEach(o -> assertThat(o.refusal().code()).isEqualTo("SEATS_UNAVAILABLE"));
            UUID winner = outcomes.stream().filter(Outcome::won).findFirst().orElseThrow().booking().id();
            for (UUID seat : forward) {
                assertThat(bookingOf(jdbc, event, seat)).isEqualTo(winner);
            }
        }
    }

    @Test
    void manyUsersAfterRandomSeatsNeverShareASeat() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 12);
        Random random = new Random(42);
        List<Callable<BookingResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 30; i++) {
            UUID user = UUID.randomUUID();
            List<UUID> wanted = new ArrayList<>(seats);
            Collections.shuffle(wanted, random);
            List<UUID> pick = List.copyOf(wanted.subList(0, 3));
            calls.add(() -> hold(user, event, pick));
        }

        List<Outcome> outcomes = race(calls);

        Set<UUID> taken = new HashSet<>();
        for (Outcome outcome : outcomes) {
            if (outcome.won()) {
                for (BookingResponse.Seat seat : outcome.booking().seats()) {
                    // A seat in two winning bookings would be a double sale.
                    assertThat(taken.add(seat.seatId())).as("seat %s given twice", seat.seatId()).isTrue();
                    assertThat(bookingOf(jdbc, event, seat.seatId())).isEqualTo(outcome.booking().id());
                }
            } else {
                assertThat(outcome.refusal().code()).isEqualTo("SEATS_UNAVAILABLE");
            }
        }
        assertThat(outcomes.stream().filter(Outcome::won)).isNotEmpty();
        long heldSeats = seats.stream().filter(s -> "HELD".equals(stateOf(jdbc, event, s))).count();
        assertThat(heldSeats).isEqualTo(taken.size());
    }

    // ---- all or nothing

    @Test
    void ifOneSeatIsTakenNothingIsHeldAndTheTakenOneIsNamed() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 3);
        sell(jdbc, event, seats.get(0));
        UUID user = UUID.randomUUID();

        ApiException refused = refusal(() -> hold(user, event, List.of(seats.get(0), seats.get(1))));

        assertThat(refused.code()).isEqualTo("SEATS_UNAVAILABLE");
        assertThat(refused.properties().get("unavailableSeatIds")).isEqualTo(List.of(seats.get(0)));
        assertThat(stateOf(jdbc, event, seats.get(1))).isEqualTo("AVAILABLE");
        assertThat(bookingCount(jdbc, event)).isZero();
        // The refused request left no idempotency key behind, so a retry runs again.
        assertThat(jdbc.queryForObject("SELECT count(*) FROM idempotency_keys WHERE user_id = ?", Integer.class,
                user)).isZero();
    }

    @Test
    void aSeatThatIsHeldByAnotherCustomerCannotBeTaken() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 2);
        holdBySql(jdbc, event, seats.get(0), "5 minutes");

        assertThat(refusal(() -> hold(UUID.randomUUID(), event, List.of(seats.get(0)))).code())
                .isEqualTo("SEATS_UNAVAILABLE");
    }

    // ---- test obligation 4: expiry without the sweeper

    @Test
    void aHoldThatHasExpiredCanBeTakenAtOnceEvenIfNoJobHasRun() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 2);
        holdBySql(jdbc, event, seats.get(0), "-1 minute");

        BookingResponse booking = hold(UUID.randomUUID(), event, List.of(seats.get(0)));

        assertThat(bookingOf(jdbc, event, seats.get(0))).isEqualTo(booking.id());
        assertThat(stateOf(jdbc, event, seats.get(0))).isEqualTo("HELD");
    }

    // ---- the rules around one customer

    @Test
    void aCustomerHoldsOnlyOnceForAnEventUntilTheHoldExpires() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 4);
        UUID user = UUID.randomUUID();
        BookingResponse first = hold(user, event, List.of(seats.get(0)));

        assertThat(refusal(() -> hold(user, event, List.of(seats.get(1)))).code()).isEqualTo("ACTIVE_HOLD_EXISTS");
        assertThat(stateOf(jdbc, event, seats.get(1))).isEqualTo("AVAILABLE");

        jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 minute' WHERE booking_id = ?", first.id());
        BookingResponse second = hold(user, event, List.of(seats.get(1)));
        assertThat(second.id()).isNotEqualTo(first.id());
    }

    @Test
    void theSameCustomerMayHoldSeatsOfAnotherEventAtTheSameTime() {
        UUID one = UUID.randomUUID();
        UUID two = UUID.randomUUID();
        List<UUID> seatsOne = eventWithSeats(jdbc, one, 2);
        List<UUID> seatsTwo = eventWithSeats(jdbc, two, 2);
        UUID user = UUID.randomUUID();

        hold(user, one, List.of(seatsOne.get(0)));
        assertThat(hold(user, two, List.of(seatsTwo.get(0))).eventId()).isEqualTo(two);
    }

    @Test
    void twoSimultaneousRequestsFromOneCustomerGiveOneHold() throws Exception {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 4);
        UUID user = UUID.randomUUID();

        List<Outcome> outcomes = race(List.of(
                () -> hold(user, event, List.of(seats.get(0))),
                () -> hold(user, event, List.of(seats.get(1)))));

        assertThat(outcomes.stream().filter(Outcome::won)).hasSize(1);
        assertThat(outcomes.stream().filter(o -> !o.won()).findFirst().orElseThrow().refusal().code())
                .isEqualTo("ACTIVE_HOLD_EXISTS");
        assertThat(bookingCount(jdbc, event)).isEqualTo(1);
    }

    // ---- idempotency (ADR-0009, part C)

    @Test
    void theSameRequestWithTheSameKeyGetsTheSameBookingAndHoldsOnce() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 3);
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();

        BookingResponse first = holds.hold(user, key, event, List.of(seats.get(0), seats.get(1)));
        // Even with the seats listed in the other order: it is the same request.
        BookingResponse again = holds.hold(user, key, event, List.of(seats.get(1), seats.get(0)));

        assertThat(again.id()).isEqualTo(first.id());
        assertThat(again.expiresAt()).isEqualTo(first.expiresAt());
        assertThat(again.seats()).isEqualTo(first.seats());
        assertThat(bookingCount(jdbc, event)).isEqualTo(1);
    }

    @Test
    void theSameKeyForADifferentRequestIsRefused() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 3);
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        holds.hold(user, key, event, List.of(seats.get(0)));

        assertThat(refusal(() -> holds.hold(user, key, event, List.of(seats.get(1)))).code())
                .isEqualTo("IDEMPOTENCY_KEY_REUSED");
        assertThat(stateOf(jdbc, event, seats.get(1))).isEqualTo("AVAILABLE");
    }

    @Test
    void aKeyBelongsToTheUserThatSentIt() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 3);
        UUID key = UUID.randomUUID();
        BookingResponse mine = holds.hold(UUID.randomUUID(), key, event, List.of(seats.get(0)));

        // Another user sends the same key value: it is a new request of theirs, not a replay of the first.
        BookingResponse theirs = holds.hold(UUID.randomUUID(), key, event, List.of(seats.get(1)));

        assertThat(theirs.id()).isNotEqualTo(mine.id());
    }

    @Test
    void tenSimultaneousRetriesWithOneKeyMakeOneBooking() throws Exception {
        UUID event = UUID.randomUUID();
        UUID seat = eventWithSeats(jdbc, event, 2).get(0);
        UUID user = UUID.randomUUID();
        UUID key = UUID.randomUUID();
        List<Callable<BookingResponse>> calls = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            calls.add(() -> holds.hold(user, key, event, List.of(seat)));
        }

        List<Outcome> outcomes = race(calls);

        assertThat(outcomes).allMatch(Outcome::won);
        assertThat(outcomes.stream().map(o -> o.booking().id()).distinct()).hasSize(1);
        assertThat(bookingCount(jdbc, event)).isEqualTo(1);
    }

    // ---- bad input

    @Test
    void anEventThatIsNotOnSaleIs404() {
        assertThat(refusal(() -> hold(UUID.randomUUID(), UUID.randomUUID(), List.of(UUID.randomUUID()))).code())
                .isEqualTo("EVENT_NOT_ON_SALE");
    }

    @Test
    void aSeatThatIsNotOfThisEventIsRefusedAndNothingIsHeld() {
        UUID event = UUID.randomUUID();
        UUID other = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 2);
        List<UUID> otherSeats = eventWithSeats(jdbc, other, 2);

        assertThatThrownBy(() -> hold(UUID.randomUUID(), event, List.of(seats.get(0), otherSeats.get(0))))
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> hold(UUID.randomUUID(), event, List.of(UUID.randomUUID())))
                .isInstanceOf(InvalidRequestException.class);
        assertThat(stateOf(jdbc, event, seats.get(0))).isEqualTo("AVAILABLE");
        assertThat(stateOf(jdbc, other, otherSeats.get(0))).isEqualTo("AVAILABLE");
    }

    @Test
    void theSeatListIsChecked() {
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 10);

        assertThatThrownBy(() -> hold(UUID.randomUUID(), event, List.of())).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> hold(UUID.randomUUID(), event, seats.subList(0, 9)))
                .isInstanceOf(InvalidRequestException.class);   // 9 seats, the limit is 8
        assertThatThrownBy(() -> hold(UUID.randomUUID(), event, List.of(seats.get(0), seats.get(0))))
                .isInstanceOf(InvalidRequestException.class);
        // Eight is allowed.
        assertThat(hold(UUID.randomUUID(), event, seats.subList(0, 8)).seats()).hasSize(8);
    }

    // ---- limiting the wait

    @Test
    void aSeatThatIsLockedForTooLongGivesTryAgainInsteadOfWaiting() throws Exception {
        UUID event = UUID.randomUUID();
        UUID seat = eventWithSeats(jdbc, event, 2).get(0);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            // Another transaction keeps the seat row locked, as a slow request would.
            Future<?> blocker = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                jdbc.queryForList("SELECT 1 FROM event_seats WHERE event_id = ? AND seat_id = ? FOR UPDATE",
                        event, seat);
                locked.countDown();
                try {
                    release.await(30, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            long started = System.nanoTime();

            ApiException refused = refusal(() -> hold(UUID.randomUUID(), event, List.of(seat)));

            assertThat(refused.code()).isEqualTo("SEATS_BUSY");
            // The wait is the lock timeout (2 seconds), not forever.
            assertThat(TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started)).isBetween(1_500L, 10_000L);
            release.countDown();
            blocker.get(10, TimeUnit.SECONDS);
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        // After the lock is gone the seat can be held.
        assertThat(hold(UUID.randomUUID(), event, List.of(seat)).status()).isEqualTo("HELD");
    }
}
