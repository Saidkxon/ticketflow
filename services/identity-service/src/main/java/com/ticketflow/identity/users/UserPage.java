package com.ticketflow.identity.users;

import java.util.List;

/** One page of users (contracts/identity.openapi.yaml, schema UserPage). */
public record UserPage(List<UserResponse> items, int page, int size, long totalItems) {
}
