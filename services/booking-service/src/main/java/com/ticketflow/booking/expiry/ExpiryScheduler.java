package com.ticketflow.booking.expiry;

import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import com.ticketflow.booking.web.CorrelationIdFilter;

/**
 * Runs the sweeper by itself. A run that fails (for example the database is down) is logged and the next run tries
 * again; nothing is lost, because the bookings stay HELD until a run succeeds, and their seats are free anyway.
 * Each run gets its own correlation ID, so its log lines belong together.
 */
@Component
@ConditionalOnProperty(prefix = "ticketflow.booking.expiry", name = "schedule-enabled", havingValue = "true",
        matchIfMissing = true)
class ExpiryScheduler {

    private static final Logger log = LoggerFactory.getLogger(ExpiryScheduler.class);

    private final ExpirySweeper sweeper;

    ExpiryScheduler(ExpirySweeper sweeper) {
        this.sweeper = sweeper;
    }

    @Scheduled(fixedDelayString = "${ticketflow.booking.expiry.interval:PT10S}")
    void tick() {
        MDC.put(CorrelationIdFilter.MDC_KEY, UUID.randomUUID().toString());
        try {
            sweeper.sweep();
        } catch (RuntimeException e) {
            log.warn("expiry sweeper run failed ({}); it will try again", e.getClass().getSimpleName());
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }
}
