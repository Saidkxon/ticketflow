package com.ticketflow.gateway.limits;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

class FixedWindowLimiterTest {

    /** A clock the test moves by hand. */
    private static final class ManualClock extends Clock {
        private long millis = 1_000_000;

        void advanceSeconds(long seconds) {
            millis += seconds * 1000;
        }

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(millis);
        }

        @Override
        public long millis() {
            return millis;
        }
    }

    private final ManualClock clock = new ManualClock();
    private final FixedWindowLimiter limiter = new FixedWindowLimiter(clock);

    @Test
    void allowsUpToTheLimitThenBlocks() {
        for (int i = 0; i < 3; i++) {
            assertThat(limiter.tryAcquire("a", 3).allowed()).isTrue();
        }

        FixedWindowLimiter.Decision blocked = limiter.tryAcquire("a", 3);

        assertThat(blocked.allowed()).isFalse();
        assertThat(blocked.retryAfterSeconds()).isEqualTo(60);
    }

    @Test
    void retryAfterShrinksAsTheWindowPasses() {
        limiter.tryAcquire("a", 1);
        clock.advanceSeconds(45);

        assertThat(limiter.tryAcquire("a", 1).retryAfterSeconds()).isEqualTo(15);
    }

    @Test
    void aNewWindowStartsAfterAMinute() {
        limiter.tryAcquire("a", 1);
        assertThat(limiter.tryAcquire("a", 1).allowed()).isFalse();

        clock.advanceSeconds(60);

        assertThat(limiter.tryAcquire("a", 1).allowed()).isTrue();
    }

    @Test
    void keysAreCountedSeparately() {
        limiter.tryAcquire("a", 1);

        assertThat(limiter.tryAcquire("a", 1).allowed()).isFalse();
        assertThat(limiter.tryAcquire("b", 1).allowed()).isTrue();
    }

    @Test
    void aFullTableLetsNewKeysThroughInsteadOfBlockingEveryone() {
        for (int i = 0; i < FixedWindowLimiter.MAX_KEYS; i++) {
            limiter.tryAcquire("key-" + i, 1);
        }

        assertThat(limiter.tryAcquire("one-more-key", 1).allowed()).isTrue();
        assertThat(limiter.tryAcquire("one-more-key", 1).allowed()).isTrue();
        // Known keys are still limited.
        assertThat(limiter.tryAcquire("key-0", 1).allowed()).isFalse();
    }

    @Test
    void expiredWindowsAreForgottenWhenTheTableFills() {
        for (int i = 0; i < FixedWindowLimiter.MAX_KEYS; i++) {
            limiter.tryAcquire("key-" + i, 1);
        }
        clock.advanceSeconds(61);

        limiter.tryAcquire("fresh", 1);

        assertThat(limiter.tryAcquire("fresh", 1).allowed()).isFalse();
    }
}
