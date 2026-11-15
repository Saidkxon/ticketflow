package com.ticketflow.identity.users;

import com.ticketflow.identity.web.FieldProblem;
import com.ticketflow.identity.web.InvalidRequestException;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** What Admins do with users: find them and change their roles (FR-2, ADR-0004). The Admin check is made before this is called. */
@Service
public class UserAdminService {

    private static final Logger log = LoggerFactory.getLogger(UserAdminService.class);

    private final UserRepository users;

    public UserAdminService(UserRepository users) {
        this.users = users;
    }

    /** Sorted by creation time, then ID, so pages are stable. The email filter is an exact, case-insensitive match. */
    @Transactional(readOnly = true)
    public UserPage list(String email, int page, int size) {
        if (email != null && !email.isBlank()) {
            Optional<User> found = users.findByEmail(email.trim().toLowerCase(Locale.ROOT));
            List<UserResponse> items = page == 0 ? found.map(UserResponse::from).stream().toList() : List.of();
            return new UserPage(items, page, size, found.isPresent() ? 1 : 0);
        }
        Page<User> result = users.findAll(PageRequest.of(page, size, Sort.by("createdAt", "id")));
        return new UserPage(result.getContent().stream().map(UserResponse::from).toList(),
                page, size, result.getTotalElements());
    }

    /**
     * Replaces the roles of a user. CUSTOMER is always kept. An Admin cannot remove their own ADMIN
     * role, so the system cannot end up without an Admin. The change reaches the user's token at
     * its next refresh (ADR-0004).
     */
    @Transactional
    public UserResponse setRoles(UUID actorId, UUID targetId, List<Role> requested) {
        if (requested == null || requested.contains(null)) {
            throw new InvalidRequestException(List.of(new FieldProblem("roles", "is required and must list valid roles")));
        }
        Set<Role> roles = EnumSet.of(Role.CUSTOMER);
        roles.addAll(requested);

        User user = users.findById(targetId).orElseThrow(UserNotFoundException::new);
        if (actorId.equals(targetId) && user.getRoles().contains(Role.ADMIN) && !roles.contains(Role.ADMIN)) {
            throw new InvalidRequestException(
                    List.of(new FieldProblem("roles", "an admin cannot remove their own ADMIN role")));
        }
        user.replaceRoles(roles);
        // Security event (ADR-0004): who changed whose roles, never any secret.
        log.info("roles changed actorId={} targetId={} roles={}", actorId, targetId, roles);
        return UserResponse.from(user);
    }
}
