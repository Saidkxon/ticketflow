package com.ticketflow.payment.payments;

import com.ticketflow.payment.outbox.OutboxRepository;
import com.ticketflow.payment.provider.ChargeOutcome;
import com.ticketflow.payment.provider.ChargeRequest;
import com.ticketflow.payment.provider.ChargeState;
import com.ticketflow.payment.provider.PaymentProvider;
import com.ticketflow.payment.provider.RefundRequest;
import com.ticketflow.payment.provider.ProviderProperties;
import com.ticketflow.payment.provider.ProviderTimeoutException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Takes pending payments to the provider and records what it answers (ADR-0006, rules R4 and R5).
 *
 * <ul>
 *   <li>The first time, the payment is charged; afterwards, while it is undecided, the provider is only asked what
 *       became of the charge. Both use the idempotency key "bookingId:attempt", so the provider never charges twice.
 *   <li>A timeout, and an answer of "still processing", leave the payment PENDING and look again later. A payment is
 *       failed only when the provider says it declined, or confirms by a lookup that it holds no charge (R5).
 *   <li>The answer is applied by {@link #settle}, which only changes a payment that is still PENDING. When the provider
 *       reports a result twice, or a late answer contradicts a decided payment, nothing changes and no second event is
 *       written (R4). The status change and the event are written in one transaction (ADR-0003).
 *   <li>Each payment is handled in its own transaction that holds its row lock, so several processors can run at the same
 *       time without handling the same payment (FOR UPDATE SKIP LOCKED).
 * </ul>
 */
@Service
public class PaymentProcessor {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessor.class);

    private final PaymentRepository payments;
    private final PaymentProvider provider;
    private final OutboxRepository outbox;
    private final TransactionTemplate transaction;
    private final ProviderProperties properties;

    PaymentProcessor(PaymentRepository payments, PaymentProvider provider, OutboxRepository outbox,
            PlatformTransactionManager transactions, ProviderProperties properties) {
        this.payments = payments;
        this.provider = provider;
        this.outbox = outbox;
        this.transaction = new TransactionTemplate(transactions);
        this.properties = properties;
    }

    /** What one run did. */
    public record Result(int settled, int postponed, int refunded) {
    }

    /** Handles up to {@code batchSize} payments that are due. */
    public Result runOnce() {
        int settled = 0;
        int postponed = 0;
        for (int i = 0; i < properties.batchSize(); i++) {
            Boolean decided = transaction.execute(status -> processNext());
            if (decided == null) {
                break; // nothing is due
            }
            if (decided) {
                settled++;
            } else {
                postponed++;
            }
        }
        int refunded = 0;
        for (int i = 0; i < properties.batchSize(); i++) {
            Boolean done = transaction.execute(status -> processNextRefund());
            if (done == null) {
                break; // no refund is due
            }
            if (done) {
                refunded++;
            } else {
                postponed++;
            }
        }
        if (settled > 0 || postponed > 0 || refunded > 0) {
            log.info("payment processor settled {} payments, refunded {} and postponed {}", settled, refunded, postponed);
        }
        return new Result(settled, postponed, refunded);
    }

    /** @return null when nothing is due, true when the payment was settled, false when it stays pending */
    private Boolean processNext() {
        Optional<Payment> claimed = payments.claimNextDue();
        if (claimed.isEmpty()) {
            return null;
        }
        Payment payment = claimed.get();
        String key = payment.bookingId() + ":" + payment.attemptNumber();
        ChargeOutcome outcome;
        try {
            outcome = payment.checkCount() == 0
                    ? provider.charge(new ChargeRequest(key, payment.amountMinor(), payment.currency(), payment.scenario()))
                    : provider.lookup(key);
        } catch (ProviderTimeoutException e) {
            log.info("provider did not answer for paymentId={}; the payment stays pending", payment.paymentId());
            outcome = ChargeOutcome.processing();
        }
        if (outcome.state() == ChargeState.PROCESSING) {
            payments.postpone(payment.paymentId(), properties.recheckInterval());
            return false;
        }
        settle(payment.paymentId(), outcome);
        return true;
    }

    /**
     * Sends or checks one refund, like {@link #processNext()} does for a charge (R4, R5): the first time the refund is
     * asked for; afterwards only looked up. A timeout and "still processing" leave it REFUND_PENDING, and a provider that
     * confirms it holds no refund for the key is asked again (safe: the key makes the refund happen once).
     *
     * @return null when no refund is due, true when the payment was refunded, false when it stays REFUND_PENDING
     */
    private Boolean processNextRefund() {
        Optional<Payment> claimed = payments.claimNextRefundDue();
        if (claimed.isEmpty()) {
            return null;
        }
        Payment payment = claimed.get();
        String key = payment.bookingId() + ":" + payment.attemptNumber();
        RefundRequest request = new RefundRequest(key, payment.amountMinor(), payment.currency(), payment.scenario());
        ChargeOutcome outcome;
        try {
            outcome = payment.checkCount() == 0 ? provider.refund(request) : provider.lookupRefund(key);
            if (outcome.state() == ChargeState.NOT_CHARGED) {
                outcome = provider.refund(request);
            }
        } catch (ProviderTimeoutException e) {
            log.info("provider did not answer about the refund of paymentId={}; it stays pending", payment.paymentId());
            outcome = ChargeOutcome.processing();
        }
        if (outcome.state() == ChargeState.SUCCEEDED) {
            settleRefund(payment.paymentId(), outcome);
            return true;
        }
        if (outcome.state() == ChargeState.DECLINED) {
            // The provider refuses to give the money back. Nothing here can fix that: a person has to look at it.
            log.error("provider refused the refund of paymentId={} ({}); it stays pending", payment.paymentId(),
                    outcome.reason());
        }
        payments.postpone(payment.paymentId(), properties.recheckInterval());
        return false;
    }

    /**
     * Applies the provider's answer that a refund is done. Like {@link #settle}: only the first report that finds the
     * payment REFUND_PENDING changes it and writes {@code RefundSucceeded}; a repeated report changes nothing (R4).
     *
     * @return true when this call completed the refund
     */
    boolean settleRefund(UUID paymentId, ChargeOutcome outcome) {
        Boolean changed = transaction.execute(status -> {
            boolean first = false;
            int reports = Math.max(1, outcome.deliveries());
            for (int report = 0; report < reports; report++) {
                Optional<Payment> refunded = payments.settleRefund(paymentId, outcome.reference());
                if (refunded.isEmpty()) {
                    log.info("payment {} was refunded already; a repeated answer changes nothing", paymentId);
                    continue;
                }
                Payment payment = refunded.get();
                UUID messageId = UUID.randomUUID();
                outbox.insert(messageId, "Payment", payment.paymentId(), PaymentResultMessage.REFUNDED,
                        PaymentResultMessage.refunded(messageId, payment));
                log.info("payment {} is REFUNDED bookingId={} attempt={}", payment.paymentId(), payment.bookingId(),
                        payment.attemptNumber());
                first = true;
            }
            return first;
        });
        return Boolean.TRUE.equals(changed);
    }

    /**
     * Applies a final answer of the provider. Safe to call any number of times with the same or a contradicting answer:
     * only the first one that finds the payment PENDING changes it and writes the event.
     *
     * @return true when this call decided the payment
     */
    boolean settle(UUID paymentId, ChargeOutcome outcome) {
        Boolean changed = transaction.execute(status -> {
            // A provider may report the same result more than once; every report goes through the same check.
            boolean first = false;
            int reports = Math.max(1, outcome.deliveries());
            for (int report = 0; report < reports; report++) {
                first |= settleOnce(paymentId, outcome);
            }
            return first;
        });
        return Boolean.TRUE.equals(changed);
    }

    private boolean settleOnce(UUID paymentId, ChargeOutcome outcome) {
        Optional<Payment> settled = switch (outcome.state()) {
            case SUCCEEDED -> payments.settle(paymentId, PaymentStatus.SUCCEEDED, outcome.reference(), null);
            case DECLINED, NOT_CHARGED -> payments.settle(paymentId, PaymentStatus.FAILED, null, outcome.reason());
            case PROCESSING -> throw new IllegalArgumentException("An undecided answer does not settle a payment");
        };
        if (settled.isEmpty()) {
            log.info("payment {} was decided already; a repeated answer changes nothing", paymentId);
            return false;
        }
        Payment payment = settled.get();
        UUID messageId = UUID.randomUUID();
        String eventType = payment.status() == PaymentStatus.SUCCEEDED
                ? PaymentResultMessage.SUCCEEDED : PaymentResultMessage.FAILED;
        String json = payment.status() == PaymentStatus.SUCCEEDED
                ? PaymentResultMessage.succeeded(messageId, payment)
                : PaymentResultMessage.failed(messageId, payment);
        outbox.insert(messageId, "Payment", payment.paymentId(), eventType, json);
        log.info("payment {} is {} bookingId={} attempt={}", payment.paymentId(), payment.status(),
                payment.bookingId(), payment.attemptNumber());
        return true;
    }
}
