package com.ticketflow.payment.payments;

import com.ticketflow.payment.provider.TestCardScenario;
import java.sql.Timestamp;
import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes the payments table (ADR-0005). */
@Repository
class PaymentRepository {

    private static final String COLUMNS =
            "payment_id, booking_id, attempt_number, amount_minor, currency, status, created_at, updated_at, "
                    + "scenario, card_last4, provider_reference, failure_reason, check_count";

    private final JdbcClient jdbc;

    PaymentRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Creates the attempt unless one with the same booking and attempt number exists. The unique constraint decides, so
     * two requests at the same moment cannot both insert: one inserts, the other waits for it and then inserts nothing.
     *
     * @return the new payment, or empty when the attempt already existed
     */
    Optional<Payment> insertIfAbsent(UUID paymentId, UUID bookingId, int attemptNumber, long amountMinor,
            String currency, TestCardScenario scenario, String cardLast4) {
        return jdbc.sql("""
                        INSERT INTO payments (payment_id, booking_id, attempt_number, amount_minor, currency, status,
                                              scenario, card_last4)
                        VALUES (:paymentId, :bookingId, :attemptNumber, :amountMinor, :currency, 'PENDING',
                                :scenario, :cardLast4)
                        ON CONFLICT (booking_id, attempt_number) DO NOTHING
                        RETURNING\s""" + COLUMNS)
                .param("paymentId", paymentId)
                .param("bookingId", bookingId)
                .param("attemptNumber", attemptNumber)
                .param("amountMinor", amountMinor)
                .param("currency", currency)
                .param("scenario", scenario.name())
                .param("cardLast4", cardLast4)
                .query(PaymentRepository::map)
                .optional();
    }

    /**
     * Takes the next pending payment whose check is due and locks it until the transaction ends. A payment that another
     * processor holds is skipped, so two processors never work on the same payment. Must run inside a transaction.
     */
    Optional<Payment> claimNextDue() {
        return jdbc.sql("SELECT " + COLUMNS + """
                         FROM payments WHERE status = 'PENDING' AND next_check_at <= clock_timestamp()
                        ORDER BY next_check_at, payment_id LIMIT 1 FOR UPDATE SKIP LOCKED""")
                .query(PaymentRepository::map)
                .optional();
    }

    /** The provider has not decided: count the check and look again after {@code wait}. The payment stays PENDING. */
    void postpone(UUID paymentId, Duration wait) {
        jdbc.sql("""
                        UPDATE payments SET check_count = check_count + 1,
                                            next_check_at = clock_timestamp() + make_interval(secs => :seconds)
                        WHERE payment_id = :paymentId AND status = 'PENDING'""")
                .param("seconds", wait.toMillis() / 1000.0)
                .param("paymentId", paymentId)
                .update();
    }

    /**
     * Records the final answer, but only for a payment that is still PENDING: a payment that is decided never changes,
     * so an answer that arrives twice, or late and contradicting, changes nothing.
     *
     * @return the payment as it is now, or empty when it was decided already
     */
    Optional<Payment> settle(UUID paymentId, PaymentStatus status, String providerReference, String failureReason) {
        return jdbc.sql("""
                        UPDATE payments SET status = :status, provider_reference = :reference,
                                            failure_reason = :reason, updated_at = clock_timestamp()
                        WHERE payment_id = :paymentId AND status = 'PENDING'
                        RETURNING\s""" + COLUMNS)
                .param("status", status.name())
                .param("reference", providerReference)
                .param("reason", failureReason)
                .param("paymentId", paymentId)
                .query(PaymentRepository::map)
                .optional();
    }

    Optional<Payment> findByAttempt(UUID bookingId, int attemptNumber) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE booking_id = :bookingId AND attempt_number = :attempt")
                .param("bookingId", bookingId)
                .param("attempt", attemptNumber)
                .query(PaymentRepository::map)
                .optional();
    }

    Optional<Payment> findById(UUID paymentId) {
        return jdbc.sql("SELECT " + COLUMNS + " FROM payments WHERE payment_id = :paymentId")
                .param("paymentId", paymentId)
                .query(PaymentRepository::map)
                .optional();
    }

    private static Payment map(java.sql.ResultSet rs, int rowNumber) throws java.sql.SQLException {
        Timestamp created = rs.getTimestamp("created_at");
        Timestamp updated = rs.getTimestamp("updated_at");
        return new Payment(
                rs.getObject("payment_id", UUID.class),
                rs.getObject("booking_id", UUID.class),
                rs.getInt("attempt_number"),
                rs.getLong("amount_minor"),
                rs.getString("currency"),
                PaymentStatus.valueOf(rs.getString("status")),
                created.toInstant(),
                updated.toInstant(),
                TestCardScenario.valueOf(rs.getString("scenario")),
                rs.getString("card_last4"),
                rs.getString("provider_reference"),
                rs.getString("failure_reason"),
                rs.getInt("check_count"));
    }
}
