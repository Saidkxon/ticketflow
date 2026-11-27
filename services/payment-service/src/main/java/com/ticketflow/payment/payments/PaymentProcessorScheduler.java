package com.ticketflow.payment.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the payment processor by itself. A run that fails (for example the database is down) is logged and tried again
 * at the next tick; the payments are still PENDING, so nothing is lost.
 */
@Component
@ConditionalOnProperty(prefix = "ticketflow.payment.provider", name = "schedule-enabled", havingValue = "true",
        matchIfMissing = true)
class PaymentProcessorScheduler {

    private static final Logger log = LoggerFactory.getLogger(PaymentProcessorScheduler.class);

    private final PaymentProcessor processor;

    PaymentProcessorScheduler(PaymentProcessor processor) {
        this.processor = processor;
    }

    @Scheduled(fixedDelayString = "${ticketflow.payment.provider.interval:PT1S}")
    void tick() {
        try {
            processor.runOnce();
        } catch (RuntimeException e) {
            log.warn("payment processor run failed ({}); it is tried again", e.getClass().getSimpleName());
        }
    }
}
