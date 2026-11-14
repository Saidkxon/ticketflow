package com.ticketflow.identity.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** ADR-0004: slowing down password guessing per account. */
class LoginThrottleTest {

    /** A clock the test can move forward. */
    private static final class MovableClock extends Clock {
        private Instant now = Instant.parse("2026-11-14T10:00:00Z");

        void advance(Duration time) {
            now = now.plus(time);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private MovableClock clock;
    private LoginThrottle throttle;

    @BeforeEach
    void setUp() {
        clock = new MovableClock();
        throttle = new LoginThrottle(clock);
    }

    private long secondsBlocked(String key) {
        try {
            throttle.checkAllowed(key);
            return 0;
        } catch (TooManyAttemptsException e) {
            return e.retryAfterSeconds();
        }
    }

    private void fail(String key, int times) {
        for (int i = 0; i < times; i++) {
            throttle.recordFailure(key);
        }
    }

    @Test
    void theFirstFourWrongPasswordsAreNotBlocked() {
        fail("a@x.com", 4);

        assertThat(secondsBlocked("a@x.com")).isZero();
    }

    @Test
    void theFifthBlocksForThirtySecondsAndOtherEmailsAreNotAffected() {
        fail("a@x.com", 5);

        assertThat(secondsBlocked("a@x.com")).isEqualTo(30);
        assertThat(secondsBlocked("b@x.com")).isZero();

        clock.advance(Duration.ofSeconds(10));
        assertThat(secondsBlocked("a@x.com")).isEqualTo(20);

        clock.advance(Duration.ofSeconds(21));
        assertThat(secondsBlocked("a@x.com")).isZero();
    }

    @Test
    void everyFurtherFailureDoublesTheBlockUpToFiveMinutes() {
        fail("a@x.com", 5);
        long[] expected = {60, 120, 240, 300, 300};
        for (long seconds : expected) {
            clock.advance(Duration.ofSeconds(301));
            throttle.recordFailure("a@x.com");
            assertThat(secondsBlocked("a@x.com")).isEqualTo(seconds);
        }
    }

    @Test
    void aSuccessfulLoginStartsOver() {
        fail("a@x.com", 5);
        clock.advance(Duration.ofSeconds(31));

        throttle.recordSuccess("a@x.com");

        assertThat(secondsBlocked("a@x.com")).isZero();
        fail("a@x.com", 4);
        assertThat(secondsBlocked("a@x.com")).isZero();
    }

    @Test
    void fifteenQuietMinutesForgetOldFailures() {
        fail("a@x.com", 4);
        clock.advance(Duration.ofMinutes(16));

        fail("a@x.com", 4);

        assertThat(secondsBlocked("a@x.com")).isZero();
    }

    @Test
    void theMemoryUseIsBounded() {
        for (int i = 0; i < LoginThrottle.MAX_TRACKED + 2_000; i++) {
            throttle.recordFailure("e" + i + "@x.com");
        }

        // A new email is no longer tracked, so it is never blocked ...
        fail("late@x.com", 6);
        assertThat(secondsBlocked("late@x.com")).isZero();
        // ... but an email that was already tracked still is.
        fail("e1@x.com", 5);
        assertThat(secondsBlocked("e1@x.com")).isPositive();
        assertThatCode(() -> throttle.checkAllowed("never-seen@x.com")).doesNotThrowAnyException();
        assertThatThrownBy(() -> throttle.checkAllowed("e1@x.com")).isInstanceOf(TooManyAttemptsException.class);
    }
}
