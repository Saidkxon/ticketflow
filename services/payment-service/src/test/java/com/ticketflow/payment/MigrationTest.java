package com.ticketflow.payment;

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
    void paymentsTableHasTheColumnsAndTheIdempotencyKey() {
        List<String> columns = jdbc.queryForList(
                "select column_name from information_schema.columns "
                        + "where table_name = 'payments' order by ordinal_position",
                String.class);

        assertThat(columns).containsExactly("payment_id", "booking_id", "attempt_number", "amount_minor",
                "currency", "status", "created_at", "updated_at");
    }

    @Test
    void theDatabaseRefusesASecondPaymentForTheSameBookingAndAttempt() {
        java.util.UUID booking = java.util.UUID.randomUUID();
        String insert = "insert into payments (payment_id, booking_id, attempt_number, amount_minor, currency, status) "
                + "values (?, ?, 1, 1000, 'UZS', 'PENDING')";
        jdbc.update(insert, java.util.UUID.randomUUID(), booking);

        org.assertj.core.api.Assertions.assertThatThrownBy(
                () -> jdbc.update(insert, java.util.UUID.randomUUID(), booking))
                .isInstanceOf(org.springframework.dao.DuplicateKeyException.class);
    }

    @Test
    void theDatabaseRefusesBadAmountsCurrenciesAndStatuses() {
        String insert = "insert into payments (payment_id, booking_id, attempt_number, amount_minor, currency, status) "
                + "values (?, ?, ?, ?, ?, ?)";

        for (Object[] row : new Object[][] {
                {0, "UZS", "PENDING", 1}, {-1, "UZS", "PENDING", 1}, {1000, "uzs", "PENDING", 1},
                {1000, "UZS", "REFUNDED", 1}, {1000, "UZS", "PENDING", 0}}) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> jdbc.update(insert, java.util.UUID.randomUUID(),
                    java.util.UUID.randomUUID(), row[3], row[0], row[1], row[2]))
                    .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
        }
    }
}
