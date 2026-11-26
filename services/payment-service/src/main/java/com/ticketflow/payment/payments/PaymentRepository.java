package com.ticketflow.payment.payments;

import java.sql.Timestamp;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** Reads and writes the payments table (ADR-0005). */
@Repository
class PaymentRepository {

    private static final String COLUMNS =
            "payment_id, booking_id, attempt_number, amount_minor, currency, status, created_at, updated_at";

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
            String currency) {
        return jdbc.sql("""
                        INSERT INTO payments (payment_id, booking_id, attempt_number, amount_minor, currency, status)
                        VALUES (:paymentId, :bookingId, :attemptNumber, :amountMinor, :currency, 'PENDING')
                        ON CONFLICT (booking_id, attempt_number) DO NOTHING
                        RETURNING\s""" + COLUMNS)
                .param("paymentId", paymentId)
                .param("bookingId", bookingId)
                .param("attemptNumber", attemptNumber)
                .param("amountMinor", amountMinor)
                .param("currency", currency)
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
                updated.toInstant());
    }
}
