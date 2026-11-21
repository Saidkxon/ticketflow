package com.ticketflow.booking.security;

import java.util.Set;
import java.util.UUID;

/** What a valid access token says. Booking only needs to know who the user is (ADR-0004). */
public record VerifiedToken(UUID userId, Set<String> roles) {
}
