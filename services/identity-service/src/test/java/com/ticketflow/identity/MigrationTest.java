package com.ticketflow.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** ADR-0005, test obligation 2: migrations apply cleanly to an empty database. */
class MigrationTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    @Test
    void migrationsAreAppliedToAnEmptyDatabase() {
        Integer applied = jdbc.queryForObject(
                "select count(*) from flyway_schema_history where success", Integer.class);

        assertThat(applied).isGreaterThanOrEqualTo(1);
    }

    @Test
    void usersTableHasTheExpectedColumns() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_name = 'users' order by ordinal_position",
                String.class);

        assertThat(columns).containsExactly(
                "id", "email", "password_hash", "display_name", "created_at", "version");
    }

    @Test
    void emailIsUniqueIgnoringCase() {
        insertUser("Ada@Example.com");

        assertThatThrownBy(() -> insertUser("ada@example.COM"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void unknownRolesAreRejected() {
        UUID id = insertUser("grace@example.com");

        assertThatThrownBy(() -> jdbc.update(
                "insert into user_roles (user_id, role) values (?, 'SUPERUSER')", id))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rolesAreRemovedWithTheirUser() {
        UUID id = insertUser("linus@example.com");
        jdbc.update("insert into user_roles (user_id, role) values (?, 'CUSTOMER')", id);

        jdbc.update("delete from users where id = ?", id);

        Integer left = jdbc.queryForObject(
                "select count(*) from user_roles where user_id = ?", Integer.class, id);
        assertThat(left).isZero();
    }

    private UUID insertUser(String email) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into users (id, email, password_hash, display_name) values (?, ?, 'x', 'Test')",
                id, email);
        return id;
    }
}
