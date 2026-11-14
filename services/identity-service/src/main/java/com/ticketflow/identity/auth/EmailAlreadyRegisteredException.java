package com.ticketflow.identity.auth;

/** Becomes 409 with code EMAIL_ALREADY_REGISTERED (see the clarification in ADR-0004). */
public class EmailAlreadyRegisteredException extends RuntimeException {

    public EmailAlreadyRegisteredException() {
        super("Email already registered");
    }
}
