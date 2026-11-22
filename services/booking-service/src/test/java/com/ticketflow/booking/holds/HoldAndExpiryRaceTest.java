package com.ticketflow.booking.holds;

import static com.ticketflow.booking.holds.HoldFixtures.bookingOf;
import static com.ticketflow.booking.holds.HoldFixtures.eventWithSeats;
import static com.ticketflow.booking.holds.HoldFixtures.stateOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import com.ticketflow.booking.expiry.ExpirySweeper;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * The sweeper and a new hold on the same seats at the same moment (ADR-0012, part B). Both lock seats in order of seat
 * ID, one booking at a time in the sweeper's case, so they cannot deadlock; and whichever comes first, the new customer
 * ends up with the seats and the old booking ends up EXPIRED.
 */
class HoldAndExpiryRaceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private HoldService holds;

    @Autowired
    private ExpirySweeper sweeper;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void noOtherExpiredBookings() {
        jdbc.update("UPDATE bookings SET status = 'EXPIRED' WHERE status = 'HELD' AND expires_at <= now()");
    }

    @Test
    void aNewHoldAndTheSweeperOnTheSameSeatsNeverDeadlockAndTheNewHolderKeepsTheSeats() throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID event = UUID.randomUUID();
            List<UUID> seats = eventWithSeats(jdbc, event, 4);
            BookingResponse old = holds.hold(UUID.randomUUID(), UUID.randomUUID(), event, seats);
            // The old hold ran out: its deadline has passed on the booking and on the seats.
            jdbc.update("UPDATE bookings SET expires_at = now() - interval '1 minute' WHERE booking_id = ?", old.id());
            jdbc.update("UPDATE event_seats SET held_until = now() - interval '1 minute' WHERE booking_id = ?", old.id());
            UUID newcomer = UUID.randomUUID();

            ExecutorService pool = Executors.newFixedThreadPool(2);
            BookingResponse taken;
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<BookingResponse> hold = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return holds.hold(newcomer, UUID.randomUUID(), event, seats);
                });
                Future<ExpirySweeper.Result> sweep = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return sweeper.sweep();
                });
                ready.await();
                go.countDown();
                taken = hold.get(60, TimeUnit.SECONDS);   // no refusal, no deadlock error
                sweep.get(60, TimeUnit.SECONDS);
            } finally {
                pool.shutdownNow();
            }

            assertThat(jdbc.queryForObject("SELECT status FROM bookings WHERE booking_id = ?", String.class, old.id()))
                    .isEqualTo("EXPIRED");
            for (UUID seat : seats) {
                assertThat(stateOf(jdbc, event, seat)).isEqualTo("HELD");
                assertThat(bookingOf(jdbc, event, seat)).isEqualTo(taken.id());
            }
        }
    }
}
