package com.ticketflow.booking.availability;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;

/** The short in-memory cache and the ETag rules (ADR-0011), without a database. */
class AvailabilityServiceTest {

    private static final AvailabilityProperties TWO_SECONDS = new AvailabilityProperties(Duration.ofSeconds(2));

    /** A source that counts its calls, with a list that can be changed. */
    private static final class CountingSource implements UnavailableSeats {
        final AtomicInteger calls = new AtomicInteger();
        volatile Optional<List<UUID>> answer = Optional.of(List.of());
        volatile long delayMillis;

        @Override
        public Optional<List<UUID>> unavailableSeatIds(UUID eventId) {
            calls.incrementAndGet();
            if (delayMillis > 0) {
                try {
                    Thread.sleep(delayMillis);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }
            return answer;
        }
    }

    @Test
    void theDatabaseIsAskedOncePerRefreshNotOncePerRequest() {
        CountingSource source = new CountingSource();
        AtomicLong clock = new AtomicLong();
        AvailabilityService service = new AvailabilityService(source, TWO_SECONDS, clock::get);
        UUID event = UUID.randomUUID();

        for (int i = 0; i < 50; i++) {
            assertThat(service.find(event)).isPresent();
        }
        assertThat(source.calls).hasValue(1);

        clock.addAndGet(Duration.ofSeconds(2).toNanos());
        service.find(event);

        assertThat(source.calls).hasValue(2);
    }

    @Test
    void aChangeShowsUpAfterTheCacheExpires() {
        CountingSource source = new CountingSource();
        AtomicLong clock = new AtomicLong();
        AvailabilityService service = new AvailabilityService(source, TWO_SECONDS, clock::get);
        UUID event = UUID.randomUUID();
        UUID seat = UUID.randomUUID();
        String before = service.find(event).orElseThrow().etag();

        source.answer = Optional.of(List.of(seat));
        assertThat(service.find(event).orElseThrow().etag()).isEqualTo(before);   // still the cached one
        clock.addAndGet(Duration.ofSeconds(3).toNanos());
        AvailabilityService.Snapshot fresh = service.find(event).orElseThrow();

        assertThat(fresh.unavailableSeatIds()).containsExactly(seat);
        assertThat(fresh.etag()).isNotEqualTo(before);
    }

    @Test
    void aZeroTtlTurnsTheCacheOff() {
        CountingSource source = new CountingSource();
        AvailabilityService service = new AvailabilityService(source,
                new AvailabilityProperties(Duration.ZERO), new AtomicLong()::get);
        UUID event = UUID.randomUUID();

        service.find(event);
        service.find(event);

        assertThat(source.calls).hasValue(2);
    }

    @Test
    void anEventThatIsNotOnSaleIsNeverCached() {
        CountingSource source = new CountingSource();
        source.answer = Optional.empty();
        AvailabilityService service = new AvailabilityService(source, TWO_SECONDS, new AtomicLong()::get);
        UUID event = UUID.randomUUID();

        assertThat(service.find(event)).isEmpty();
        assertThat(service.find(event)).isEmpty();
        assertThat(source.calls).hasValue(2);

        // It goes on sale: the next request sees it at once, because "not on sale" was not remembered.
        source.answer = Optional.of(List.of());
        assertThat(service.find(event)).isPresent();
    }

    @Test
    void manyRequestsAtTheSameTimeShareOneDatabaseQuery() throws Exception {
        CountingSource source = new CountingSource();
        source.delayMillis = 200;
        AvailabilityService service = new AvailabilityService(source, TWO_SECONDS);
        UUID event = UUID.randomUUID();
        int pollers = 30;
        ExecutorService pool = Executors.newFixedThreadPool(pollers);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Boolean>> results = new ArrayList<>();
        for (int i = 0; i < pollers; i++) {
            results.add(pool.submit(() -> {
                start.await();
                return service.find(event).isPresent();
            }));
        }
        start.countDown();

        for (Future<Boolean> result : results) {
            assertThat(result.get()).isTrue();
        }
        pool.shutdown();

        assertThat(source.calls).hasValue(1);
    }

    // ---- ETag

    @Test
    void theSameSeatsGiveTheSameEtagAndAnyChangeGivesAnother() {
        UUID event = UUID.randomUUID();
        UUID a = UUID.randomUUID();
        UUID b = UUID.randomUUID();

        String one = AvailabilityService.etagOf(event, List.of(a, b));

        assertThat(AvailabilityService.etagOf(event, List.of(a, b))).isEqualTo(one);
        assertThat(AvailabilityService.etagOf(event, List.of(a))).isNotEqualTo(one);
        assertThat(AvailabilityService.etagOf(event, List.of())).isNotEqualTo(one);
        assertThat(AvailabilityService.etagOf(UUID.randomUUID(), List.of(a, b))).isNotEqualTo(one);
        assertThat(one).matches("^\"[0-9a-f]{32}\"$");
    }

    @Test
    void ifNoneMatchIsUnderstoodInItsUsualForms() {
        String etag = "\"abc123\"";

        assertThat(AvailabilityService.etagMatches("\"abc123\"", etag)).isTrue();
        assertThat(AvailabilityService.etagMatches("W/\"abc123\"", etag)).isTrue();
        assertThat(AvailabilityService.etagMatches("\"zzz\", \"abc123\"", etag)).isTrue();
        assertThat(AvailabilityService.etagMatches("*", etag)).isTrue();

        assertThat(AvailabilityService.etagMatches("\"zzz\"", etag)).isFalse();
        assertThat(AvailabilityService.etagMatches("abc123", etag)).isFalse();
        assertThat(AvailabilityService.etagMatches("", etag)).isFalse();
        assertThat(AvailabilityService.etagMatches(null, etag)).isFalse();
    }
}
