package com.ticketflow.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
    void outboxTableHasTheColumnsTheRelayNeeds() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_name = 'outbox_events' order by ordinal_position",
                String.class);

        assertThat(columns).containsExactly("id", "aggregate_type", "aggregate_id",
                "event_type", "payload", "created_at", "published_at");
    }
}
