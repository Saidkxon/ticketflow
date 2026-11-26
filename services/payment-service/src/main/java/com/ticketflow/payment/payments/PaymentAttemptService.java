package com.ticketflow.payment.payments;

import com.ticketflow.payment.web.ApiException;
import java.util.Optional;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Creates payment attempts (ADR-0006, rule R2; FR-11). The call is idempotent: the key is the booking and the attempt
 * number, so a request that is repeated returns the payment that the first one created and nothing is created or
 * charged a second time. The same key with a different amount or currency is not a repeat but a mistake (or an attack)
 * and is refused, so a key can never be used to change what was asked.
 */
@Service
public class PaymentAttemptService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAttemptService.class);

    private final PaymentRepository payments;

    PaymentAttemptService(PaymentRepository payments) {
        this.payments = payments;
    }

    public Payment create(UUID bookingId, int attemptNumber, long amountMinor, String currency) {
        Optional<Payment> created = payments.insertIfAbsent(UUID.randomUUID(), bookingId, attemptNumber,
                amountMinor, currency);
        if (created.isPresent()) {
            log.info("payment attempt created paymentId={} bookingId={} attempt={}",
                    created.get().paymentId(), bookingId, attemptNumber);
            return created.get();
        }
        // The attempt exists already (this is a repeat). Another request that created it has committed by now: the insert
        // above waits for it before it decides to do nothing.
        Payment existing = payments.findByAttempt(bookingId, attemptNumber)
                .orElseThrow(() -> new IllegalStateException("A conflicting payment attempt vanished"));
        if (existing.amountMinor() != amountMinor || !existing.currency().equals(currency)) {
            log.warn("idempotency key reused for another request bookingId={} attempt={}", bookingId, attemptNumber);
            throw ApiException.idempotencyKeyReused();
        }
        log.info("payment attempt repeated paymentId={} bookingId={} attempt={}",
                existing.paymentId(), bookingId, attemptNumber);
        return existing;
    }

    public Payment get(UUID paymentId) {
        return payments.findById(paymentId).orElseThrow(ApiException::paymentNotFound);
    }
}
