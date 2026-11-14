package com.ticketflow.identity.tokens;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for access tokens (ADR-0004). The key directory and the signing key ID have no
 * default: a missing value stops the service at startup instead of running with a wrong key.
 */
@ConfigurationProperties("ticketflow.identity.jwt")
public record JwtProperties(
        @DefaultValue("ticketflow-identity") String issuer,
        @DefaultValue("ticketflow-api") String audience,
        @DefaultValue("15m") Duration accessTokenTtl,
        String keyDirectory,
        String signingKeyId) {
}
