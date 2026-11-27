package com.ticketflow.payment.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.payment.AbstractPostgresIntegrationTest;
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

/**
 * ADR-0006 R4 and R7, test obligation 8 (the refund part): asking for a refund starts one refund, however often and
 * however many times at once the request arrives, and only a payment that was charged can be refunded.
 */
class RefundServiceTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private RefundService refunds;

    @Autowired
    private PaymentProcessor processor;

    @Autowired
    private PaymentAttemptService attempts;

    @Autowired
    private JdbcTemplate jdbc;

    @BeforeEach
    void startEmpty() {
        jdbc.update("DELETE FROM payments");
        jdbc.update("DELETE FROM outbox_events");
        jdbc.update("DELETE FROM sim_provider_charges");
    }

    private Payment succeededPayment() {
        Payment payment = attempts.create(UUID.randomUUID(), 1, 150_000, "UZS", "4242424242424242");
        processor.runOnce();
        assertThat(statusOf(payment.paymentId())).isEqualTo("SUCCEEDED");
        return payment;
    }

    private String statusOf(UUID paymentId) {
        return jdbc.queryForObject("select status from payments where payment_id = ?", String.class, paymentId);
    }

    @Test
    void aSucceededPaymentMovesToRefundPendingWithItsReason() {
        Payment payment = succeededPayment();

        RefundService.Outcome outcome = refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");

        assertThat(outcome).isEqualTo(RefundService.Outcome.STARTED);
        assertThat(statusOf(payment.paymentId())).isEqualTo("REFUND_PENDING");
        assertThat(jdbc.queryForObject("select refund_reason from payments where payment_id = ?", String.class,
                payment.paymentId())).isEqualTo("booking_cancelled");
    }

    @Test
    void theSameRequestAgainChangesNothing() {
        Payment payment = succeededPayment();
        refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");

        RefundService.Outcome again = refunds.request(payment.paymentId(), payment.bookingId(), "booking_expired");

        assertThat(again).isEqualTo(RefundService.Outcome.ALREADY_REQUESTED);
        assertThat(jdbc.queryForObject("select refund_reason from payments where payment_id = ?", String.class,
                payment.paymentId())).isEqualTo("booking_cancelled");
    }

    @Test
    void aRequestForARefundedPaymentChangesNothing() {
        Payment payment = succeededPayment();
        refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");
        processor.runOnce();
        assertThat(statusOf(payment.paymentId())).isEqualTo("REFUNDED");

        assertThat(refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled"))
                .isEqualTo(RefundService.Outcome.ALREADY_REQUESTED);
        assertThat(statusOf(payment.paymentId())).isEqualTo("REFUNDED");
    }

    @Test
    void aRequestThatCanNeverWorkIsRefusedAsNotPossible() {
        Payment succeeded = succeededPayment();
        Payment failed = attempts.create(UUID.randomUUID(), 1, 150_000, "UZS", "4000000000000002");
        processor.runOnce();

        assertThatThrownBy(() -> refunds.request(UUID.randomUUID(), UUID.randomUUID(), "booking_expired"))
                .isInstanceOf(RefundNotPossibleException.class);
        // Another booking's payment is not refunded on this booking's word.
        assertThatThrownBy(() -> refunds.request(succeeded.paymentId(), UUID.randomUUID(), "booking_expired"))
                .isInstanceOf(RefundNotPossibleException.class);
        assertThatThrownBy(() -> refunds.request(succeeded.paymentId(), succeeded.bookingId(), "because"))
                .isInstanceOf(RefundNotPossibleException.class);
        assertThatThrownBy(() -> refunds.request(failed.paymentId(), failed.bookingId(), "booking_expired"))
                .isInstanceOf(RefundNotPossibleException.class);
        assertThat(statusOf(succeeded.paymentId())).isEqualTo("SUCCEEDED");
        assertThat(statusOf(failed.paymentId())).isEqualTo("FAILED");
    }

    @Test
    void aPaymentThatIsNotDecidedYetCanBeAskedAgainLater() {
        Payment pending = attempts.create(UUID.randomUUID(), 1, 150_000, "UZS", "4242424242424242");

        assertThatThrownBy(() -> refunds.request(pending.paymentId(), pending.bookingId(), "booking_expired"))
                .isInstanceOf(IllegalStateException.class);
        assertThat(statusOf(pending.paymentId())).isEqualTo("PENDING");
    }

    @Test
    void manyRequestsAtTheSameTimeStartExactlyOneRefund() throws Exception {
        Payment payment = succeededPayment();
        int callers = 10;
        ExecutorService pool = Executors.newFixedThreadPool(callers);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<RefundService.Outcome>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return refunds.request(payment.paymentId(), payment.bookingId(), "booking_cancelled");
                }));
            }
            go.countDown();
            int started = 0;
            for (Future<RefundService.Outcome> future : futures) {
                if (future.get() == RefundService.Outcome.STARTED) {
                    started++;
                }
            }
            assertThat(started).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(statusOf(payment.paymentId())).isEqualTo("REFUND_PENDING");
    }
}
