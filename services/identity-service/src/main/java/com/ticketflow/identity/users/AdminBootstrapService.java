package com.ticketflow.identity.users;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Gives the ADMIN role to an existing user. Used once, at startup, to create the first Admin. */
@Service
public class AdminBootstrapService {

    public enum Result { PROMOTED, ALREADY_ADMIN, NOT_FOUND }

    private static final Logger log = LoggerFactory.getLogger(AdminBootstrapService.class);

    private final UserRepository users;

    public AdminBootstrapService(UserRepository users) {
        this.users = users;
    }

    @Transactional
    public Result promote(String email) {
        Optional<User> found = users.findByEmail(email.trim().toLowerCase(Locale.ROOT));
        if (found.isEmpty()) {
            return Result.NOT_FOUND;
        }
        User user = found.get();
        if (user.getRoles().contains(Role.ADMIN)) {
            return Result.ALREADY_ADMIN;
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        roles.addAll(user.getRoles());
        roles.add(Role.ADMIN);
        user.replaceRoles(roles);
        // Security event (ADR-0004).
        log.warn("bootstrap: ADMIN role granted userId={}", user.getId());
        return Result.PROMOTED;
    }
}
