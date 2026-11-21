package com.ticketflow.booking.security;

/** The public keys could not be fetched from Identity, so no token can be checked right now. Becomes 503. */
public class KeysUnavailableException extends RuntimeException {

    public KeysUnavailableException(Throwable cause) {
        super("Token keys unavailable", cause);
    }
}
