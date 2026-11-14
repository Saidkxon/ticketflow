package com.ticketflow.identity.auth;

/** Too many failed logins for one email. Becomes 429 RATE_LIMITED with a Retry-After header. */
public class TooManyAttemptsException extends RuntimeException {

    private final long retryAfterSeconds;

    public TooManyAttemptsException(long retryAfterSeconds) {
        super("Too many attempts");
        this.retryAfterSeconds = retryAfterSeconds;
    }

    public long retryAfterSeconds() {
        return retryAfterSeconds;
    }
}
