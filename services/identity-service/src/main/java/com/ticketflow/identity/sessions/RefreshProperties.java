package com.ticketflow.identity.sessions;

import java.time.Duration;
import java.util.List;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * Settings for refresh tokens and their cookie (ADR-0004).
 *
 * @param ttl            how long a refresh token is valid (7 days by default)
 * @param cookieSecure   whether the cookie is sent only over HTTPS; keep true (browsers still accept it on localhost)
 * @param allowedOrigins the web app origins that may call the refresh and logout endpoints
 */
@ConfigurationProperties("ticketflow.identity.refresh")
public record RefreshProperties(
        @DefaultValue("7d") Duration ttl,
        @DefaultValue("true") boolean cookieSecure,
        @DefaultValue({"http://localhost:18090", "http://localhost:5173"}) List<String> allowedOrigins) {
}
