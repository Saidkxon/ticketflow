package com.ticketflow.identity.users;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** The public view of a user (contracts/identity.openapi.yaml, schema User). Never carries the password hash. */
public record UserResponse(UUID id, String email, String displayName, List<Role> roles, Instant createdAt) {

    public static UserResponse from(User user) {
        return new UserResponse(user.getId(), user.getEmail(), user.getDisplayName(),
                user.getRoles().stream().sorted().toList(), user.getCreatedAt());
    }
}
