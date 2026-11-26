package com.ticketflow.payment.outbox;

import java.time.Duration;
import java.time.Instant;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Runs the relay by itself. After a failure it waits longer and longer (2, 4, 8 ... at most 30 seconds) so a broker
 * that is down is not hammered, and it logs once when the trouble starts and once when it is over, not every second.
 */
@Component
@ConditionalOnProperty(prefix = "ticketflow.payment.outbox", name = "schedule-enabled", havingValue = "true",
        matchIfMissing = true)
class OutboxRelayScheduler {

    private static final Logger log = LoggerFactory.getLogger(OutboxRelayScheduler.class);
    private static final long MAX_BACKOFF_SECONDS = 30;

    private final OutboxRelay relay;
    private int failures;
    private boolean waitingForQueues;
    private Instant notBefore = Instant.EPOCH;

    OutboxRelayScheduler(OutboxRelay relay) {
        this.relay = relay;
    }

    @Scheduled(fixedDelayString = "${ticketflow.payment.outbox.interval:PT1S}")
    void tick() {
        if (Instant.now().isBefore(notBefore)) {
            return;
        }
        OutboxRelay.Outcome outcome;
        try {
            outcome = relay.runOnce();
        } catch (RuntimeException e) { // for example the database is down: the next run tries again
            outcome = new OutboxRelay.Outcome(0, 0, "the outbox could not be read (" + e.getClass().getSimpleName() + ")");
        }
        if (outcome.failed()) {
            failures++;
            if (failures == 1) {
                log.warn("outbox relay is waiting: {}; unpublished events stay in the outbox and are retried", outcome.failure());
            }
            notBefore = Instant.now().plus(Duration.ofSeconds(Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(failures, 5))));
        } else {
            if (failures > 0) {
                log.info("outbox relay works again after {} failed runs", failures);
            }
            failures = 0;
            notBefore = Instant.EPOCH;
        }
        if (outcome.published() > 0) {
            log.info("outbox relay published {} events", outcome.published());
        }
        if (outcome.unroutable() > 0 && !waitingForQueues) {
            waitingForQueues = true;
            log.warn("outbox relay: {} events wait because no queue receives them yet; they are retried", outcome.unroutable());
        } else if (outcome.unroutable() == 0) {
            waitingForQueues = false;
        }
    }
}
