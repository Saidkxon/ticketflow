package com.ticketflow.identity.users;

import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** Users (contracts/identity.openapi.yaml). Only the signed-in user's own data so far. */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    private final UserRepository users;

    public UserController(UserRepository users) {
        this.users = users;
    }

    /** The user ID comes from the verified token, never from the request. Roles and name are read fresh from the database. */
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public UserResponse me(Authentication authentication) {
        UUID id = UUID.fromString(authentication.getName());
        return users.findById(id).map(UserResponse::from).orElseThrow(TokenUserNotFoundException::new);
    }
}
