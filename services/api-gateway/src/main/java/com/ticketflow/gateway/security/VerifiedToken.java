package com.ticketflow.gateway.security;

import java.util.Set;
import java.util.UUID;

/** What a valid access token says. The gateway only authenticates; roles are enforced by the services. */
public record VerifiedToken(UUID userId, Set<String> roles) {
}
