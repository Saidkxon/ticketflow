package com.ticketflow.identity.tokens;

import java.time.Duration;
import java.util.Map;
import java.util.Set;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The services that may ask Identity for a service token (ADR-0004, Part D), and what each may ask for. This is
 * configuration, not data: the list changes together with the code that makes the calls.
 *
 * @param tokenTtl how long a service token is valid; short, because the caller can simply ask again
 * @param clients  the calling services by name (for example {@code booking-service})
 */
@ConfigurationProperties("ticketflow.identity.service-clients")
public record ServiceClientProperties(
        @DefaultValue("5m") Duration tokenTtl,
        @DefaultValue Map<String, Client> clients) {

    /**
     * One calling service.
     *
     * @param secret  its secret, from the environment, never from the repository (NFR-7); empty means the service
     *                cannot get tokens at all (so a forgotten secret closes the door instead of opening it)
     * @param allowed for each target service, the scopes this caller may ask for
     */
    public record Client(String secret, @DefaultValue Map<String, Set<String>> allowed) {

        /** The secret is a credential and must never end up in a log line. */
        @Override
        public String toString() {
            return "Client[secret=<hidden>, allowed=" + allowed + "]";
        }
    }
}
