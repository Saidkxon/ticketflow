package com.ticketflow.booking.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Handles one event of Payment exactly once in effect (ADR-0006 R2): the event ID is recorded and the handler runs in
 * one transaction. A duplicate finds its ID recorded and does nothing; a failure in the handler rolls the record back
 * too, so the redelivered event is handled again.
 */
@Service
class PaymentEventProcessor {

    static final String CONSUMER = "booking-payment-events";

    private static final Logger log = LoggerFactory.getLogger(PaymentEventProcessor.class);

    private final ProcessedEvents processed;
    private final PaymentEventHandler handler;
    private final TransactionTemplate transaction;

    PaymentEventProcessor(ProcessedEvents processed, PaymentEventHandler handler,
            PlatformTransactionManager transactions) {
        this.processed = processed;
        this.handler = handler;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** @return true when the event was handled now, false when it was handled before */
    boolean process(PaymentEvent event) {
        Boolean first = transaction.execute(status -> {
            if (!processed.markProcessed(CONSUMER, event.eventId(), event.kind().eventType())) {
                return false;
            }
            handler.handle(event);
            return true;
        });
        if (!Boolean.TRUE.equals(first)) {
            log.info("payment event {} eventId={} was handled before; nothing changes", event.kind().eventType(),
                    event.eventId());
            return false;
        }
        return true;
    }
}
