package com.ticketflow.identity.users;

import com.ticketflow.identity.web.FieldProblem;
import com.ticketflow.identity.web.InvalidRequestException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Users (contracts/identity.openapi.yaml). The signed-in user's own data, and the Admin-only
 * list and role change; the Admin rule is in SecurityConfig (everything under /users except /me).
 */
@RestController
@RequestMapping("/api/v1/users")
public class UserController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;
    static final int MAX_EMAIL_LENGTH = 254;

    private final UserRepository users;
    private final UserAdminService admin;

    public UserController(UserRepository users, UserAdminService admin) {
        this.users = users;
        this.admin = admin;
    }

    @GetMapping
    public UserPage list(@RequestParam(required = false) String email,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        List<FieldProblem> problems = new ArrayList<>();
        if (page < 0) {
            problems.add(new FieldProblem("page", "must be 0 or greater"));
        }
        if (size < 1 || size > MAX_SIZE) {
            problems.add(new FieldProblem("size", "must be between 1 and " + MAX_SIZE));
        }
        if (email != null && email.length() > MAX_EMAIL_LENGTH) {
            problems.add(new FieldProblem("email", "must be at most " + MAX_EMAIL_LENGTH + " characters"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
        return admin.list(email, page, size);
    }

    @PutMapping("/{userId}/roles")
    public UserResponse setRoles(@PathVariable UUID userId, @RequestBody SetRolesRequest request,
            Authentication authentication) {
        return admin.setRoles(UUID.fromString(authentication.getName()), userId, request.roles());
    }

    /** The user ID comes from the verified token, never from the request. Roles and name are read fresh from the database. */
    @GetMapping("/me")
    @Transactional(readOnly = true)
    public UserResponse me(Authentication authentication) {
        UUID id = UUID.fromString(authentication.getName());
        return users.findById(id).map(UserResponse::from).orElseThrow(TokenUserNotFoundException::new);
    }
}
