package com.ticketflow.identity.sessions;

/**
 * The refresh token is missing, unknown, expired, revoked or already used. Becomes 401
 * INVALID_REFRESH_TOKEN, and the answer never says which of these it was (ADR-0004).
 */
public class InvalidRefreshTokenException extends RuntimeException {

    public InvalidRefreshTokenException() {
        super("Invalid refresh token");
    }
}
