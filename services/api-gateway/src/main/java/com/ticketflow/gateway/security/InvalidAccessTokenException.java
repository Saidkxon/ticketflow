package com.ticketflow.gateway.security;

/** The access token is missing, malformed, expired, wrongly signed or has wrong claims. It never says which. */
public class InvalidAccessTokenException extends RuntimeException {

    public InvalidAccessTokenException() {
        super("Invalid access token");
    }

    public InvalidAccessTokenException(Throwable cause) {
        super("Invalid access token", cause);
    }
}
