package com.ticketflow.identity.users;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.identity.AbstractPostgresIntegrationTest;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

/** The first Admin is made by promoting a user who already registered, never by registration (ADR-0004). */
class AdminBootstrapTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private AdminBootstrapService bootstrap;

    @Autowired
    private UserRepository users;

    private String newUser(Role... roles) {
        String email = "user-" + UUID.randomUUID() + "@example.com";
        users.saveAndFlush(new User(UUID.randomUUID(), email, "{bcrypt}hash", "Test User", Set.of(roles)));
        return email;
    }

    @Test
    void anExistingUserBecomesAdminAndKeepsTheirOtherRoles() {
        String email = newUser(Role.CUSTOMER, Role.ORGANIZER);

        assertThat(bootstrap.promote(email)).isEqualTo(AdminBootstrapService.Result.PROMOTED);

        assertThat(users.findByEmail(email).orElseThrow().getRoles())
                .containsExactlyInAnyOrder(Role.CUSTOMER, Role.ORGANIZER, Role.ADMIN);
    }

    @Test
    void runningItAgainChangesNothing() {
        String email = newUser(Role.CUSTOMER);
        bootstrap.promote(email);

        assertThat(bootstrap.promote(email)).isEqualTo(AdminBootstrapService.Result.ALREADY_ADMIN);
    }

    @Test
    void theEmailMatchesIgnoringLetterCaseAndSpaces() {
        String email = newUser(Role.CUSTOMER);

        assertThat(bootstrap.promote("  " + email.toUpperCase() + " ")).isEqualTo(AdminBootstrapService.Result.PROMOTED);
    }

    @Test
    void anUnknownEmailCreatesNobody() {
        String email = "nobody-" + UUID.randomUUID() + "@example.com";

        assertThat(bootstrap.promote(email)).isEqualTo(AdminBootstrapService.Result.NOT_FOUND);
        assertThat(users.existsByEmail(email)).isFalse();
    }
}
