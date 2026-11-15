package com.ticketflow.identity.users;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

/**
 * The bootstrap of the first Admin (ADR-0004). When adminEmail is set, the already registered user
 * with this email gets the ADMIN role at startup. Empty means nothing happens.
 */
@ConfigurationProperties("ticketflow.identity.bootstrap")
public record BootstrapProperties(@DefaultValue("") String adminEmail) {
}
