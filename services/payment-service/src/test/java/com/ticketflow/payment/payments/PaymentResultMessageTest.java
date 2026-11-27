package com.ticketflow.payment.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.payment.provider.TestCardScenario;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** The event bodies match contracts/events/payment-succeeded.v1.schema.json and payment-failed.v1.schema.json. */
class PaymentResultMessageTest {

    private static final UUID MESSAGE = UUID.fromString("11111111-1111-4111-8111-111111111111");
    private static final UUID PAYMENT = UUID.fromString("22222222-2222-4222-8222-222222222222");
    private static final UUID BOOKING = UUID.fromString("33333333-3333-4333-8333-333333333333");

    private static Payment payment(PaymentStatus status, String reason) {
        return new Payment(PAYMENT, BOOKING, 2, 150_000, "UZS", status,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-01T10:00:07.912Z"),
                TestCardScenario.SUCCESS, "4242", status == PaymentStatus.SUCCEEDED ? "sim_1" : null, reason, 1, null, null);
    }

    @Test
    void aSucceededPaymentBecomesAnEnvelopeWithItsAmountAndNoCardData() {
        String json = PaymentResultMessage.succeeded(MESSAGE, payment(PaymentStatus.SUCCEEDED, null));

        assertThat(json).isEqualTo("{\"eventId\":\"" + MESSAGE + "\",\"eventType\":\"PaymentSucceeded\","
                + "\"eventVersion\":1,\"occurredAt\":\"2026-11-01T10:00:07Z\",\"producer\":\"payment-service\","
                + "\"correlationId\":null,\"aggregateId\":\"" + PAYMENT + "\","
                + "\"data\":{\"paymentId\":\"" + PAYMENT + "\",\"bookingId\":\"" + BOOKING + "\","
                + "\"attemptNumber\":2,\"amountMinor\":150000,\"currency\":\"UZS\"}}");
        assertThat(json).doesNotContain("4242").doesNotContain("sim_1");
    }

    @Test
    void aFailedPaymentAlsoCarriesItsReason() {
        String json = PaymentResultMessage.failed(MESSAGE, payment(PaymentStatus.FAILED, "card_declined"));

        assertThat(json).contains("\"eventType\":\"PaymentFailed\"")
                .endsWith("\"currency\":\"UZS\",\"reason\":\"card_declined\"}}");
    }

    @Test
    void aRefundedPaymentCarriesTheReasonBookingGave() {
        Payment refunded = new Payment(PAYMENT, BOOKING, 2, 150_000, "UZS", PaymentStatus.REFUNDED,
                Instant.parse("2026-11-01T10:00:00Z"), Instant.parse("2026-11-01T10:05:00Z"),
                TestCardScenario.SUCCESS, "4242", "sim_1", null, 1, "booking_cancelled", "sim_2");

        String json = PaymentResultMessage.refunded(MESSAGE, refunded);

        assertThat(json).contains("\"eventType\":\"RefundSucceeded\"").contains("\"occurredAt\":\"2026-11-01T10:05:00Z\"")
                .endsWith("\"currency\":\"UZS\",\"reason\":\"booking_cancelled\"}}");
        assertThat(json).doesNotContain("sim_");
        assertThatThrownBy(() -> PaymentResultMessage.refunded(MESSAGE, payment(PaymentStatus.SUCCEEDED, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void aReasonThatIsNotACodeIsRefusedSoNothingNeedsEscaping() {
        assertThatThrownBy(() -> PaymentResultMessage.failed(MESSAGE, payment(PaymentStatus.FAILED, "say \"hi\"")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> PaymentResultMessage.failed(MESSAGE, payment(PaymentStatus.FAILED, null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
