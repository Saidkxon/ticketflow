package com.ticketflow.payment.payments;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Starts the refund of a payment when Booking asks for it with {@code RefundRequested} (ADR-0006 R4 and R7; FR-9). The
 * MVP refunds in full, so the request names a payment and a reason, not an amount.
 *
 * <p>Asking is idempotent: only a SUCCEEDED payment of that booking moves to REFUND_PENDING, in one conditional update,
 * so a request that is redelivered, or arrives twice at the same moment, starts one refund (test obligation 8). The
 * refund itself is made by {@link PaymentProcessor}.
 */
@Service
public class RefundService {

    /** Why Booking asks for money back; the codes are those of contracts/events/refund-requested.v1.schema.json. */
    static final Set<String> REASONS = Set.of("booking_expired", "seats_unavailable", "booking_cancelled");

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);

    private final PaymentRepository payments;

    RefundService(PaymentRepository payments) {
        this.payments = payments;
    }

    /** What the request did. */
    public enum Outcome {
        /** The refund was started now. */
        STARTED,
        /** A refund was started or finished before: nothing changed. */
        ALREADY_REQUESTED
    }

    /**
     * @throws RefundNotPossibleException when this request can never work (unknown payment, another booking's payment,
     *                                    an unknown reason, a payment that failed so nothing was charged); trying again
     *                                    does not help
     * @throws IllegalStateException      when the payment is not decided yet, so asking again later may work
     */
    public Outcome request(UUID paymentId, UUID bookingId, String reason) {
        if (reason == null || !REASONS.contains(reason)) {
            throw new RefundNotPossibleException("the refund reason is not one of the known codes");
        }
        if (payments.requestRefund(paymentId, bookingId, reason).isPresent()) {
            log.info("refund started paymentId={} bookingId={} reason={}", paymentId, bookingId, reason);
            return Outcome.STARTED;
        }
        Optional<Payment> payment = payments.findById(paymentId);
        if (payment.isEmpty() || !payment.get().bookingId().equals(bookingId)) {
            throw new RefundNotPossibleException("no such payment for this booking");
        }
        return switch (payment.get().status()) {
            case REFUND_PENDING, REFUNDED -> {
                log.info("refund was requested already paymentId={}; nothing changes", paymentId);
                yield Outcome.ALREADY_REQUESTED;
            }
            case FAILED -> throw new RefundNotPossibleException("the payment failed, nothing was charged");
            case PENDING -> throw new IllegalStateException("the payment is not decided yet");
            case SUCCEEDED -> throw new IllegalStateException("the payment changed while the refund was requested");
        };
    }
}
