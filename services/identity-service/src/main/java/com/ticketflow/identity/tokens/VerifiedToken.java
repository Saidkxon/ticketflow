package com.ticketflow.identity.tokens;

import com.ticketflow.identity.users.Role;
import java.util.Set;
import java.util.UUID;

/** What a valid access token says: who the user is and which roles the token carried. */
public record VerifiedToken(UUID userId, Set<Role> roles) {
}
