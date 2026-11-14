package com.ticketflow.identity.auth;

/**
 * Body of POST /api/v1/auth/register. Fields are nullable here on purpose: a missing field is
 * reported as a validation problem by {@link RegistrationService}, not as a parsing failure.
 * There is no "roles" field: a caller can never choose their own role (ADR-0004).
 */
public record RegisterRequest(String email, String password, String displayName) {

    /** The password must never end up in a log line or an error message. */
    @Override
    public String toString() {
        return "RegisterRequest[email=" + email + ", password=<hidden>, displayName=" + displayName + "]";
    }
}
