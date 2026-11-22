package com.ticketflow.booking.holds;

import static com.ticketflow.booking.holds.HoldFixtures.eventWithSeats;
import static com.ticketflow.booking.holds.HoldFixtures.stateOf;
import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import com.ticketflow.booking.web.ApiException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/** Releasing a hold while other requests work on the same seats (ADR-0012, ADR-0006). */
class ReleaseServiceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private HoldService holds;

    @Autowired
    private ReleaseService releases;

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void tenReleasesAtTheSameMomentAllAnswerCancelledAndTheSeatsAreFreeOnce() throws Exception {
        UUID user = UUID.randomUUID();
        UUID event = UUID.randomUUID();
        List<UUID> seats = eventWithSeats(jdbc, event, 4);
        BookingResponse booking = holds.hold(user, UUID.randomUUID(), event, seats);

        ExecutorService pool = Executors.newFixedThreadPool(10);
        try {
            CountDownLatch go = new CountDownLatch(1);
            List<Future<BookingResponse>> results = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                results.add(pool.submit(() -> {
                    go.await();
                    return releases.release(user, booking.id());
                }));
            }
            go.countDown();
            for (Future<BookingResponse> result : results) {
                assertThat(result.get(20, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
            }
        } finally {
            pool.shutdownNow();
        }

        for (UUID seat : seats) {
            assertThat(stateOf(jdbc, event, seat)).isEqualTo("AVAILABLE");
        }
    }

    @Test
    void aReleaseAndAnotherCustomersHoldOnTheSameSeatsNeverDeadlockAndTheSeatsEndUpWithExactlyOneOwner()
            throws Exception {
        for (int round = 0; round < 15; round++) {
            UUID event = UUID.randomUUID();
            List<UUID> seats = eventWithSeats(jdbc, event, 4);
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            BookingResponse old = holds.hold(first, UUID.randomUUID(), event, seats);

            ExecutorService pool = Executors.newFixedThreadPool(2);
            try {
                CountDownLatch ready = new CountDownLatch(2);
                CountDownLatch go = new CountDownLatch(1);
                Future<BookingResponse> release = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return releases.release(first, old.id());
                });
                Future<Object> newcomer = pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return holds.hold(second, UUID.randomUUID(), event, seats);
                    } catch (ApiException e) {
                        return e;
                    }
                });
                ready.await();
                go.countDown();

                assertThat(release.get(20, TimeUnit.SECONDS).status()).isEqualTo("CANCELLED");
                Object outcome = newcomer.get(20, TimeUnit.SECONDS);
                if (outcome instanceof ApiException refused) {
                    // The newcomer came first, before the seats were free.
                    assertThat(refused.code()).isEqualTo("SEATS_UNAVAILABLE");
                    for (UUID seat : seats) {
                        assertThat(stateOf(jdbc, event, seat)).isEqualTo("AVAILABLE");
                    }
                } else {
                    UUID newBooking = ((BookingResponse) outcome).id();
                    for (UUID seat : seats) {
                        assertThat(HoldFixtures.bookingOf(jdbc, event, seat)).isEqualTo(newBooking);
                    }
                }
            } finally {
                pool.shutdownNow();
            }
        }
    }
}
