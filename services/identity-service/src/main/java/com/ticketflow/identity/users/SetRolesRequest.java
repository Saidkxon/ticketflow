package com.ticketflow.identity.users;

import java.util.List;

/** Body of PUT /users/{id}/roles. An unknown role name or an unknown field is rejected with 400. */
public record SetRolesRequest(List<Role> roles) {
}
