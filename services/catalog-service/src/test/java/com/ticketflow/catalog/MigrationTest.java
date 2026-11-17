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

    @Test
    void theLayoutTablesExistAndSeatsHaveTheirOwnIds() {
        List<String> tables = jdbc.queryForList(
                "select table_name from information_schema.tables "
                        + "where table_name in ('price_tiers', 'sections', 'seats') order by table_name",
                String.class);
        List<String> seatColumns = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_name = 'seats' order by ordinal_position",
                String.class);

        assertThat(tables).containsExactly("price_tiers", "seats", "sections");
        assertThat(seatColumns).containsExactly("id", "event_id", "section_id", "row_label", "seat_number");
    }

    @Test
    void eventsHaveTheOrganizerColumnsAndTheyAreRequired() {
        List<String> required = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_name = 'events' and is_nullable = 'NO' "
                        + "and column_name in ('owner_id', 'venue_name', 'city') order by column_name",
                String.class);

        assertThat(required).containsExactly("city", "owner_id", "venue_name");
    }
}
