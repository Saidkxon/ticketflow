package com.ticketflow.payment.security;

import java.util.Set;

/**
 * What a valid service token says (ADR-0004, Part D): which service is calling and what it may do here.
 *
 * @param caller the calling service, for example {@code booking-service} (the {@code sub} claim)
 * @param scopes the narrow permissions the token grants, for example {@code payment.attempt.create}
 */
public record ServiceToken(String caller, Set<String> scopes) {

    public ServiceToken {
        scopes = Set.copyOf(scopes);
    }

    public boolean allows(String scope) {
        return scopes.contains(scope);
    }
}
