package com.ticketflow.booking.payments;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/** Until the saga steps exist, an event of Payment is only noted in the log; the booking is not changed. */
@Component
class RecordingPaymentEventHandler implements PaymentEventHandler {

    private static final Logger log = LoggerFactory.getLogger(RecordingPaymentEventHandler.class);

    @Override
    public void handle(PaymentEvent event) {
        log.info("payment event {} received paymentId={} bookingId={} attempt={}", event.kind().eventType(),
                event.paymentId(), event.bookingId(), event.attemptNumber());
    }
}
