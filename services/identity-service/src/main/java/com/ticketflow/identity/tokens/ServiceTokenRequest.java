package com.ticketflow.identity.tokens;

import java.util.List;

/**
 * Body of POST /internal/v1/service-tokens (contracts/identity.internal.openapi.yaml). Fields are nullable so a missing
 * one is reported as a validation problem.
 */
public record ServiceTokenRequest(String clientId, String clientSecret, String audience, List<String> scopes) {

    /** The secret must never end up in a log line or an error message. */
    @Override
    public String toString() {
        return "ServiceTokenRequest[clientId=" + clientId + ", clientSecret=<hidden>, audience=" + audience
                + ", scopes=" + scopes + "]";
    }
}
