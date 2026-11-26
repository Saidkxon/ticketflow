package com.ticketflow.identity.tokens;

/** Unknown service or wrong secret. Becomes 401 INVALID_CLIENT, identical for both. */
public class InvalidClientException extends RuntimeException {

    public InvalidClientException() {
        super("Invalid client");
    }
}
