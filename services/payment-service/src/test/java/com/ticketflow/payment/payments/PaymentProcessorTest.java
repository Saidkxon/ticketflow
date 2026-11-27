package com.ticketflow.payment.payments;

import static org.assertj.core.api.Assertions.assertThat;

import com.ticketflow.payment.AbstractPostgresIntegrationTest;
import com.ticketflow.payment.outbox.OutboxRepository;
import com.ticketflow.payment.provider.ChargeOutcome;
import com.ticketflow.payment.provider.ChargeRequest;
import com.ticketflow.payment.provider.PaymentProvider;
import com.ticketflow.payment.provider.ProviderProperties;
import com.ticketflow.payment.provider.ProviderTimeoutException;
import com.ticketflow.payment.provider.TestCardScenario;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ADR-0006, rules R4 and R5, test obligations on payments: each test card ends the way it should, an answer that arrives
 * twice changes nothing and writes one event, a timeout is never reported as a failure before the provider confirms that
 * it did not charge, and several processors never settle one payment twice.
 */
class PaymentProcessorTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private PaymentProcessor processor;

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
        // The processor takes every payment that is due, including those that other test classes left behind.
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM sim_provider_charges");
    }

    private Payment attempt(String card) {
        UUID booking = UUID.randomUUID();
        return attempts.create(booking, 1, 150_000, "UZS", card);
    }

    private String statusOf(Payment payment) {
        return jdbc.queryForObject("select status from payments where payment_id = ?", String.class, payment.paymentId());
    }

    private List<String> eventsOf(Payment payment) {
        return jdbc.queryForList("select event_type from outbox_events where aggregate_id = ? order by created_at",
                String.class, payment.paymentId());
    }

    private String payloadOf(Payment payment) {
        return jdbc.queryForObject("select payload::text from outbox_events where aggregate_id = ?", String.class,
                payment.paymentId());
    }

    private void makeDue(Payment payment) {
        jdbc.update("update payments set next_check_at = clock_timestamp() - interval '1 second' where payment_id = ?",
                payment.paymentId());
    }

    // ---- the test cards

    @Test
    void theSuccessCardSucceedsAndPaymentSucceededIsWrittenToTheOutbox() {
        Payment payment = attempt("4242424242424242");

        PaymentProcessor.Result result = processor.runOnce();

        assertThat(result.settled()).isEqualTo(1);
        assertThat(statusOf(payment)).isEqualTo("SUCCEEDED");
        assertThat(jdbc.queryForObject("select provider_reference from payments where payment_id = ?", String.class,
                payment.paymentId())).startsWith("sim_");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");
        assertThat(payloadOf(payment))
                .contains("\"bookingId\": \"" + payment.bookingId() + "\"")
                .contains("\"amountMinor\": 150000")
                .contains("\"currency\": \"UZS\"")
                .contains("\"producer\": \"payment-service\"")
                .doesNotContain("4242");
    }

    @Test
    void theDeclinedCardAnUnknownNumberAndNoCardFailWithAReasonAndPaymentFailedIsWritten() {
        Payment declined = attempt("4000000000000002");
        Payment unknown = attempt("5555555555554444");
        Payment none = attempt(null);

        processor.runOnce();

        for (Payment payment : List.of(declined, unknown, none)) {
            assertThat(statusOf(payment)).isEqualTo("FAILED");
            assertThat(eventsOf(payment)).containsExactly("PaymentFailed");
        }
        assertThat(jdbc.queryForObject("select failure_reason from payments where payment_id = ?", String.class,
                declined.paymentId())).isEqualTo("card_declined");
        assertThat(jdbc.queryForObject("select failure_reason from payments where payment_id = ?", String.class,
                none.paymentId())).isEqualTo("no_payment_method");
        assertThat(payloadOf(declined)).contains("\"reason\": \"card_declined\"");
    }

    @Test
    void theDoubleReportCardSucceedsOnceAndTheSecondReportWritesNoSecondEvent() {
        Payment payment = attempt("4000000000000077");

        processor.runOnce();

        assertThat(statusOf(payment)).isEqualTo("SUCCEEDED");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");
    }

    // ---- R4: an answer that arrives again

    @Test
    void settlingAgainWithTheSameOrAContradictingAnswerChangesNothingAndWritesNothing() {
        Payment payment = attempt("4242424242424242");
        processor.runOnce();

        boolean again = processor.settle(payment.paymentId(), ChargeOutcome.succeeded("sim_other", 1));
        boolean contradiction = processor.settle(payment.paymentId(), ChargeOutcome.declined("card_declined"));

        assertThat(again).isFalse();
        assertThat(contradiction).isFalse();
        assertThat(statusOf(payment)).isEqualTo("SUCCEEDED");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");
    }

    @Test
    void aPaymentThatFailedIsNotTurnedIntoASuccessByALateAnswer() {
        Payment payment = attempt("4000000000000002");
        processor.runOnce();

        boolean late = processor.settle(payment.paymentId(), ChargeOutcome.succeeded("sim_late", 1));

        assertThat(late).isFalse();
        assertThat(statusOf(payment)).isEqualTo("FAILED");
        assertThat(eventsOf(payment)).containsExactly("PaymentFailed");
    }

    // ---- R5: a timeout is not a failure

    @Test
    void aTimeoutKeepsThePaymentPendingWithoutAnEventUntilTheProviderShowsTheLateSuccess() {
        Payment payment = attempt("4000000000000259");

        PaymentProcessor.Result first = processor.runOnce();

        assertThat(first.postponed()).isEqualTo(1);
        assertThat(first.settled()).isZero();
        assertThat(statusOf(payment)).isEqualTo("PENDING");
        assertThat(eventsOf(payment)).isEmpty();
        assertThat(jdbc.queryForObject("select check_count from payments where payment_id = ?", Integer.class,
                payment.paymentId())).isEqualTo(1);

        // Not due yet: another run leaves it alone and does not ask the provider again.
        PaymentProcessor.Result tooSoon = processor.runOnce();
        assertThat(tooSoon.postponed()).isZero();
        assertThat(tooSoon.settled()).isZero();

        // Due, but the provider still shows nothing: still pending, still no failure.
        makeDue(payment);
        PaymentProcessor.Result stillWaiting = processor.runOnce();
        assertThat(stillWaiting.postponed()).isEqualTo(1);
        assertThat(statusOf(payment)).isEqualTo("PENDING");
        assertThat(eventsOf(payment)).isEmpty();

        // The late result shows: the payment succeeds, once.
        jdbc.update("update sim_provider_charges set visible_at = clock_timestamp() - interval '1 second'");
        makeDue(payment);
        PaymentProcessor.Result late = processor.runOnce();
        assertThat(late.settled()).isEqualTo(1);
        assertThat(statusOf(payment)).isEqualTo("SUCCEEDED");
        assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");
    }

    /** A provider that does what a test script says: the charge times out, the lookup answers as scripted. */
    private static final class ScriptedProvider implements PaymentProvider {

        private final ChargeOutcome lookupAnswer;
        final List<String> calls = new ArrayList<>();

        ScriptedProvider(ChargeOutcome lookupAnswer) {
            this.lookupAnswer = lookupAnswer;
        }

        @Override
        public ChargeOutcome charge(ChargeRequest request) {
            calls.add("charge " + request.idempotencyKey());
            throw new ProviderTimeoutException("scripted");
        }

        @Override
        public ChargeOutcome lookup(String idempotencyKey) {
            calls.add("lookup " + idempotencyKey);
            return lookupAnswer;
        }
    }

    private PaymentProcessor processorWith(PaymentProvider provider) {
        return new PaymentProcessor(payments, provider, outbox, transactions, properties);
    }

    @Test
    void aTimeoutBecomesAFailureOnlyWhenALookupConfirmsThatTheProviderDidNotCharge() {
        Payment payment = attempt("4242424242424242");
        ScriptedProvider provider = new ScriptedProvider(ChargeOutcome.notCharged());
        PaymentProcessor scripted = processorWith(provider);

        scripted.runOnce();
        assertThat(statusOf(payment)).isEqualTo("PENDING");
        assertThat(eventsOf(payment)).isEmpty();

        makeDue(payment);
        scripted.runOnce();

        String key = payment.bookingId() + ":1";
        assertThat(provider.calls).containsExactly("charge " + key, "lookup " + key);
        assertThat(statusOf(payment)).isEqualTo("FAILED");
        assertThat(jdbc.queryForObject("select failure_reason from payments where payment_id = ?", String.class,
                payment.paymentId())).isEqualTo("provider_no_charge");
        assertThat(eventsOf(payment)).containsExactly("PaymentFailed");
    }

    @Test
    void aLookupThatSaysProcessingNeverFailsThePaymentHoweverOftenItIsAsked() {
        Payment payment = attempt("4242424242424242");
        PaymentProcessor scripted = processorWith(new ScriptedProvider(ChargeOutcome.processing()));

        for (int run = 0; run < 5; run++) {
            scripted.runOnce();
            makeDue(payment);
        }

        assertThat(statusOf(payment)).isEqualTo("PENDING");
        assertThat(eventsOf(payment)).isEmpty();
    }

    // ---- several processors at once

    @Test
    void severalProcessorsAtTheSameTimeSettleEveryPaymentExactlyOnce() throws Exception {
        List<Payment> created = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            created.add(attempt(i % 2 == 0 ? "4242424242424242" : "4000000000000077"));
        }
        int workers = 4;
        ExecutorService pool = Executors.newFixedThreadPool(workers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < workers; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return processor.runOnce();
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get();
            }
        } finally {
            pool.shutdownNow();
        }

        for (Payment payment : created) {
            assertThat(statusOf(payment)).isEqualTo("SUCCEEDED");
            assertThat(eventsOf(payment)).containsExactly("PaymentSucceeded");
        }
    }

    @Test
    void theSimulatorIsRegisteredForTheScenariosTheCardsStandFor() {
        assertThat(attempt("4000000000000259").scenario()).isEqualTo(TestCardScenario.TIMEOUT_THEN_SUCCESS);
    }
}
