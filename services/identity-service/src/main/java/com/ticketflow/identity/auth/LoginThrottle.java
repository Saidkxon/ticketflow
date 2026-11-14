package com.ticketflow.identity.auth;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.ConcurrentHashMap;
import org.springframework.stereotype.Component;

/**
 * Slows down password guessing per account (ADR-0004). The first four wrong passwords in a row are
 * answered normally. From the fifth, the email is blocked for 30 seconds, and every further wrong
 * password doubles the block, up to 5 minutes. While blocked, a login is refused without even
 * looking at the password, so the right password does not work either. A successful login, or 15
 * minutes without failures, starts over.
 *
 * <p>The rule is applied to the email as typed, whether or not an account exists, so it does not
 * reveal which emails are registered. The state lives in this instance's memory (the MVP runs one
 * instance); the gateway rate limit by address is a separate protection (ADR-0013, G3). A known
 * trade-off: someone can block a victim's login for a few minutes by failing on purpose.
 */
@Component
public class LoginThrottle {

    static final int FREE_FAILURES = 5;
    static final Duration BASE_BLOCK = Duration.ofSeconds(30);
    static final Duration MAX_BLOCK = Duration.ofMinutes(5);
    static final Duration FORGET_AFTER = Duration.ofMinutes(15);
    /** Upper limit of remembered emails, so a flood of made-up emails cannot fill the memory. */
    static final int MAX_TRACKED = 10_000;

    private record State(int failures, Instant lastFailure, Instant blockedUntil) {
    }

    private final Clock clock;
    private final ConcurrentHashMap<String, State> states = new ConcurrentHashMap<>();

    public LoginThrottle(Clock clock) {
        this.clock = clock;
    }

    /** Throws {@link TooManyAttemptsException} while the email is blocked. */
    public void checkAllowed(String key) {
        State state = states.get(key);
        if (state == null || state.blockedUntil() == null) {
            return;
        }
        Instant now = clock.instant();
        if (now.isBefore(state.blockedUntil())) {
            long millis = Duration.between(now, state.blockedUntil()).toMillis();
            throw new TooManyAttemptsException(Math.max(1, (millis + 999) / 1000));
        }
    }

    public void recordFailure(String key) {
        Instant now = clock.instant();
        if (states.size() >= MAX_TRACKED) {
            states.values().removeIf(state -> state.lastFailure().plus(FORGET_AFTER).isBefore(now));
            if (states.size() >= MAX_TRACKED && !states.containsKey(key)) {
                return; // full of recent failures: do not track more
            }
        }
        states.compute(key, (k, old) -> {
            boolean fresh = old == null || old.lastFailure().plus(FORGET_AFTER).isBefore(now);
            int failures = fresh ? 1 : old.failures() + 1;
            Instant blockedUntil = null;
            if (failures >= FREE_FAILURES) {
                int doublings = Math.min(failures - FREE_FAILURES, 10);
                Duration block = BASE_BLOCK.multipliedBy(1L << doublings);
                if (block.compareTo(MAX_BLOCK) > 0) {
                    block = MAX_BLOCK;
                }
                blockedUntil = now.plus(block);
            }
            return new State(failures, now, blockedUntil);
        });
    }

    public void recordSuccess(String key) {
        states.remove(key);
    }
}
