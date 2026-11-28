package com.ticketflow.booking.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

/** The database side of the payment saga (ADR-0006): the statuses, the attempt counter and the processed events. */
class PaymentSagaSchemaTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private JdbcTemplate jdbc;

    private UUID event() {
        UUID event = UUID.randomUUID();
        jdbc.update("INSERT INTO event_inventory (event_id, layout_version, currency, total_seats) "
                + "VALUES (?, 1, 'UZS', 1)", event);
        return event;
    }

    private void insertBooking(UUID event, String status) {
        jdbc.update("INSERT INTO bookings (booking_id, event_id, user_id, status, currency, total_minor, expires_at) "
                + "VALUES (?, ?, ?, ?, 'UZS', 5000, now() + interval '10 minutes')", UUID.randomUUID(), event,
                UUID.randomUUID(), status);
    }

    @Test
    void everyStatusOfTheSagaIsAcceptedAndAnyOtherIsRefused() {
        UUID event = event();

        for (String status : new String[] {"HELD", "PAYMENT_PENDING", "CONFIRMED", "EXPIRED", "CANCELLED"}) {
            insertBooking(event, status);
        }

        assertThatThrownBy(() -> insertBooking(event, "REFUNDED")).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insertBooking(event, "held")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void aNewBookingHasStartedNoPaymentsAndTheCounterStaysInThePaymentAttemptRange() {
        UUID event = event();
        UUID booking = UUID.randomUUID();
        jdbc.update("INSERT INTO bookings (booking_id, event_id, user_id, status, currency, total_minor, expires_at) "
                + "VALUES (?, ?, ?, 'HELD', 'UZS', 5000, now() + interval '10 minutes')", booking, event,
                UUID.randomUUID());

        assertThat(jdbc.queryForObject("SELECT payment_attempts FROM bookings WHERE booking_id = ?", Integer.class,
                booking)).isZero();
        jdbc.update("UPDATE bookings SET payment_attempts = 99 WHERE booking_id = ?", booking);
        assertThatThrownBy(() -> jdbc.update("UPDATE bookings SET payment_attempts = 100 WHERE booking_id = ?", booking))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE bookings SET payment_attempts = -1 WHERE booking_id = ?", booking))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void theSameEventIsRecordedOnlyOncePerConsumer() {
        UUID eventId = UUID.randomUUID();
        String insert = "INSERT INTO processed_events (consumer, event_id, event_type) VALUES (?, ?, 'PaymentSucceeded')";

        jdbc.update(insert, "one", eventId);
        jdbc.update(insert, "another", eventId);

        assertThatThrownBy(() -> jdbc.update(insert, "one", eventId)).isInstanceOf(DataIntegrityViolationException.class);
    }
}
