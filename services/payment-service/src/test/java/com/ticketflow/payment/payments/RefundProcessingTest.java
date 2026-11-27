package com.ticketflow.payment.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.payment.AbstractPostgresIntegrationTest;
import com.ticketflow.payment.outbox.OutboxRepository;
import com.ticketflow.payment.provider.ChargeOutcome;
import com.ticketflow.payment.provider.ChargeRequest;
import com.ticketflow.payment.provider.PaymentProvider;
import com.ticketflow.payment.provider.ProviderProperties;
import com.ticketflow.payment.provider.ProviderTimeoutException;
import com.ticketflow.payment.provider.RefundRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ADR-0006 R4, R5 and R7, test obligation 8: a refund is made once, an answer that arrives twice writes one
 * {@code RefundSucceeded}, a refund whose answer is slow stays pending until the provider confirms it, and a provider
 * that holds no refund for the key is asked again.
 */
class RefundProcessingTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PaymentProcessor processor;

    @Autowired
    private RefundService refunds;

    @Autowired
    private PaymentAttemptService attempts;

    @Autowired
    private PaymentRepository payments;

    @Autowired
    private OutboxRepository outbox;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private ProviderProperties properties;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startEmpty() {
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM sim_provider_charges");
    }

    private Payment paidWith(String card) {
        Payment payment = attempts.create(UUID.randomUUID(), 1, 150_000, "UZS", card);
        processor.runOnce();
        return payment;
    }

    private String statusOf(Payment payment) {
        return jdbc.queryForObject("select status from payments where payment_id = ?", String.class, payment.paymentId());
    }

    private List<String> eventsOf(Payment payment) {
        return jdbc.queryForList("select event_type from outbox_events where aggregate_id = ? order by created_at",
                String.class, payment.paymentId());
    }

    private void makeDue(Payment payment) {
        jdbc.update("update payments set next_check_at = clock_timestamp() - interval '1 second' where payment_id = ?",
                payment.paymentId());
    }

    @Test
    void aRequestedRefundIsMadeAndRefundSucceededIsWrittenOnce() {
        Payment payment = paidWith("4242424242424242");
        refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");

        PaymentProcessor.Result result = processor.runOnce();

        assertThat(result.refunded()).isEqualTo(1);
        assertThat(statusOf(payment)).isEqualTo("REFUNDED");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded", "RefundSucceeded");
        String payload = jdbc.queryForObject("select payload::text from outbox_events where aggregate_id = ? "
                + "and event_type = 'RefundSucceeded'", String.class, payment.paymentId());
        assertThat(payload).contains("\"amountMinor\": 150000").contains("\"reason\": \"booking_cancelled\"")
                .contains("\"bookingId\": \"" + payment.bookingId() + "\"");
    }

    @Test
    void aRefundRequestThatArrivesAgainAfterTheRefundWritesNothingMore() {
        Payment payment = paidWith("4242424242424242");
        refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");
        processor.runOnce();

        refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");
        PaymentProcessor.Result again = processor.runOnce();

        assertThat(again.refunded()).isZero();
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded", "RefundSucceeded");
    }

    @Test
    void aProviderThatReportsTheRefundTwiceStillWritesOneEvent() {
        Payment payment = paidWith("4000000000000077");
        refunds.request(payment.paymentId(), payment.bookingId(), "booking_expired");

        processor.runOnce();

        assertThat(statusOf(payment)).isEqualTo("REFUNDED");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded", "RefundSucceeded");
    }

    @Test
    void settlingTheRefundAgainChangesNothing() {
        Payment payment = paidWith("4242424242424242");
        refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");
        processor.runOnce();

        boolean again = processor.settleRefund(payment.paymentId(), ChargeOutcome.succeeded("sim_other", 1));

        assertThat(again).isFalse();
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded", "RefundSucceeded");
    }

    @Test
    void aPaymentThatWasNotRequestedIsNeverRefundedByTheProcessor() {
        Payment payment = paidWith("4242424242424242");

        PaymentProcessor.Result result = processor.runOnce();

        assertThat(result.refunded()).isZero();
        assertThat(statusOf(payment)).isEqualTo("SUCCEEDED");
    }

    @Test
    void aSlowRefundStaysPendingWithoutAnEventUntilTheProviderShowsIt() {
        // The charge of this card is slow too: show its success first, so there is something to refund.
        Payment payment = attempts.create(UUID.randomUUID(), 1, 150_000, "UZS", "4000000000000259");
        processor.runOnce();
        jdbc.update("update sim_provider_charges set visible_at = clock_timestamp() - interval '1 second'");
        makeDue(payment);
        processor.runOnce();
        assertThat(statusOf(payment)).isEqualTo("SUCCEEDED");
        refunds.request(payment.paymentId(), payment.bookingId(), "seats_unavailable");

        PaymentProcessor.Result first = processor.runOnce();

        assertThat(first.refunded()).isZero();
        assertThat(first.postponed()).isEqualTo(1);
        assertThat(statusOf(payment)).isEqualTo("REFUND_PENDING");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");

        makeDue(payment);
        processor.runOnce();
        assertThat(statusOf(payment)).isEqualTo("REFUND_PENDING");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");

        jdbc.update("update sim_provider_charges set visible_at = clock_timestamp() - interval '1 second'");
        makeDue(payment);
        PaymentProcessor.Result late = processor.runOnce();

        assertThat(late.refunded()).isEqualTo(1);
        assertThat(statusOf(payment)).isEqualTo("REFUNDED");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded", "RefundSucceeded");
    }

    /** A provider whose refund call times out and whose lookup answers as scripted; it records what it was asked. */
    private static final class ScriptedRefundProvider implements PaymentProvider {

        private final ChargeOutcome lookupAnswer;
        private final ChargeOutcome secondRefundAnswer;
        final List<String> calls = new ArrayList<>();

        ScriptedRefundProvider(ChargeOutcome lookupAnswer, ChargeOutcome secondRefundAnswer) {
            this.lookupAnswer = lookupAnswer;
            this.secondRefundAnswer = secondRefundAnswer;
        }

        @Override
        public ChargeOutcome charge(ChargeRequest request) {
            throw new UnsupportedOperationException("not scripted");
        }

        @Override
        public ChargeOutcome lookup(String idempotencyKey) {
            throw new UnsupportedOperationException("not scripted");
        }

        @Override
        public ChargeOutcome refund(RefundRequest request) {
            calls.add("refund " + request.idempotencyKey());
            if (calls.stream().filter(call -> call.startsWith("refund")).count() == 1) {
                throw new ProviderTimeoutException("scripted");
            }
            return secondRefundAnswer;
        }

        @Override
        public ChargeOutcome lookupRefund(String idempotencyKey) {
            calls.add("lookup " + idempotencyKey);
            return lookupAnswer;
        }
    }

    private PaymentProcessor processorWith(PaymentProvider provider) {
        return new PaymentProcessor(payments, provider, outbox, transactions, properties);
    }

    @Test
    void aProviderThatConfirmsItHoldsNoRefundIsAskedAgainAndTheRefundCompletes() {
        Payment payment = paidWith("4242424242424242");
        refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");
        ScriptedRefundProvider provider =
                new ScriptedRefundProvider(ChargeOutcome.notCharged(), ChargeOutcome.succeeded("sim_refund", 1));
        PaymentProcessor scripted = processorWith(provider);

        scripted.runOnce();
        assertThat(statusOf(payment)).isEqualTo("REFUND_PENDING");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");

        makeDue(payment);
        scripted.runOnce();

        String key = payment.bookingId() + ":1";
        assertThat(provider.calls).containsExactly("refund " + key, "lookup " + key, "refund " + key);
        assertThat(statusOf(payment)).isEqualTo("REFUNDED");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded", "RefundSucceeded");
    }

    @Test
    void aRefundTheProviderRefusesStaysPendingAndWritesNoEvent() {
        Payment payment = paidWith("4242424242424242");
        refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");
        ScriptedRefundProvider provider = new ScriptedRefundProvider(ChargeOutcome.notCharged(),
                ChargeOutcome.declined(ChargeOutcome.NO_CHARGE_TO_REFUND));
        PaymentProcessor scripted = processorWith(provider);

        for (int run = 0; run < 3; run++) {
            scripted.runOnce();
            makeDue(payment);
        }

        assertThat(statusOf(payment)).isEqualTo("REFUND_PENDING");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");
    }
}
