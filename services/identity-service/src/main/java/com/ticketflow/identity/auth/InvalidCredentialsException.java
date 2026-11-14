package com.ticketflow.identity.auth;

/** Wrong email or password. Becomes 401 INVALID_CREDENTIALS, identical for an unknown email. */
public class InvalidCredentialsException extends RuntimeException {

    public InvalidCredentialsException() {
        super("Invalid credentials");
    }
}
