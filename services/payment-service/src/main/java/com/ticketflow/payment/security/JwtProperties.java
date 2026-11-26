package com.ticketflow.payment.security;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * How the Payment service checks service tokens (ADR-0004, Part D).
 *
 * @param issuer             the issuer the tokens must carry
 * @param audience           the audience the tokens must carry: this service
 * @param jwksUri            where Identity publishes its public keys (inside the container network only)
 * @param cacheTtl           how long fetched keys are used before they are fetched again
 * @param minRefetchInterval the shortest time between two fetches caused by an unknown key ID (a key rotation);
 *                           zero switches the limit off (tests)
 */
@ConfigurationProperties("ticketflow.payment.jwt")
public record JwtProperties(
        @DefaultValue("ticketflow-identity") String issuer,
        @DefaultValue("payment-service") String audience,
        String jwksUri,
        @DefaultValue("5m") Duration cacheTtl,
        @DefaultValue("30s") Duration minRefetchInterval) {
}
