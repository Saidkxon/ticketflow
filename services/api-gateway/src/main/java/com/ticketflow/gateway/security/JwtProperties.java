package com.ticketflow.gateway.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the gateway checks access tokens (ADR-0004).
 *
 * @param issuer             the issuer the tokens must carry
 * @param audience           the audience the tokens must carry
 * @param jwksUri            where Identity publishes its public keys (inside the container network only)
 * @param cacheTtl           how long fetched keys are used before they are fetched again
 * @param minRefetchInterval the shortest time between two fetches caused by an unknown key ID (a key rotation);
 *                           zero switches the limit off (tests)
 */
@ConfigurationProperties("ticketflow.gateway.jwt")
public record JwtProperties(
        @DefaultValue("ticketflow-identity") String issuer,
        @DefaultValue("ticketflow-api") String audience,
        String jwksUri,
        @DefaultValue("5m") Duration cacheTtl,
        @DefaultValue("30s") Duration minRefetchInterval) {
}
