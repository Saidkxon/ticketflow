package com.ticketflow.catalog.security;

import java.util.Set;
import java.util.UUID;

/** What a valid access token says. Catalog decides about the ORGANIZER role itself (ADR-0004). */
public record VerifiedToken(UUID userId, Set<String> roles) {
}
