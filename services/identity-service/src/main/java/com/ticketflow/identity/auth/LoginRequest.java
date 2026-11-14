package com.ticketflow.identity.auth;

/** Body of POST /api/v1/auth/login. Fields are nullable so a missing one is reported as a validation problem. */
public record LoginRequest(String email, String password) {

    /** The password must never end up in a log line or an error message. */
    @Override
    public String toString() {
        return "LoginRequest[email=" + email + ", password=<hidden>]";
    }
}
