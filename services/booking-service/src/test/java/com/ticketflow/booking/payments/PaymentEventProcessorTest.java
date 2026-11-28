package com.ticketflow.booking.payments;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * ADR-0006 R2 (test obligation 3, the part about events): an event is handled once in effect. The event ID and the
 * handler's work are one transaction, so a duplicate does nothing and a failing handler leaves nothing behind.
 */
class PaymentEventProcessorTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private ProcessedEvents processed;

    @Autowired
    private PlatformTransactionManager transactions;

    @Autowired
    private JdbcTemplate jdbc;

    /** A handler that records what it was given, can be told to fail, and writes a row so a rollback can be seen. */
    private final class ScriptedHandler implements PaymentEventHandler {

        final List<PaymentEvent> handled = new ArrayList<>();
        int failures;

        @Override
        public void handle(PaymentEvent event) {
            jdbc.update("INSERT INTO processed_events (consumer, event_id, event_type) VALUES ('handler-work', ?, 'x')",
                    event.eventId());
            if (failures > 0) {
                failures--;
                throw new IllegalStateException("scripted failure");
            }
            handled.add(event);
        }
    }

    private static PaymentEvent event(UUID eventId, PaymentEventKind kind) {
        return new PaymentEvent(eventId, kind, UUID.randomUUID(), UUID.randomUUID(), 1, 150_000, "UZS",
                kind == PaymentEventKind.PAYMENT_SUCCEEDED ? null : "card_declined");
    }

    private int recorded(UUID eventId) {
        return jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = ? AND event_id = ?",
                Integer.class, PaymentEventProcessor.CONSUMER, eventId);
    }

    @Test
    void anEventIsHandledOnceAndItsIdIsRecorded() {
        ScriptedHandler handler = new ScriptedHandler();
        PaymentEventProcessor processor = new PaymentEventProcessor(processed, handler, transactions);
        PaymentEvent event = event(UUID.randomUUID(), PaymentEventKind.PAYMENT_SUCCEEDED);

        boolean first = processor.process(event);

        assertThat(first).isTrue();
        assertThat(handler.handled).containsExactly(event);
        assertThat(recorded(event.eventId())).isEqualTo(1);
    }

    @Test
    void theSameEventAgainIsRecognizedAndTheHandlerIsNotCalled() {
        ScriptedHandler handler = new ScriptedHandler();
        PaymentEventProcessor processor = new PaymentEventProcessor(processed, handler, transactions);
        PaymentEvent event = event(UUID.randomUUID(), PaymentEventKind.PAYMENT_FAILED);
        processor.process(event);

        boolean again = processor.process(event);

        assertThat(again).isFalse();
        assertThat(handler.handled).hasSize(1);
    }

    @Test
    void twoDifferentEventsAreBothHandled() {
        ScriptedHandler handler = new ScriptedHandler();
        PaymentEventProcessor processor = new PaymentEventProcessor(processed, handler, transactions);

        processor.process(event(UUID.randomUUID(), PaymentEventKind.PAYMENT_SUCCEEDED));
        processor.process(event(UUID.randomUUID(), PaymentEventKind.REFUND_SUCCEEDED));

        assertThat(handler.handled).hasSize(2);
    }

    @Test
    void aHandlerThatFailsLeavesNothingBehindSoTheRedeliveredEventIsHandledAgain() {
        ScriptedHandler handler = new ScriptedHandler();
        handler.failures = 1;
        PaymentEventProcessor processor = new PaymentEventProcessor(processed, handler, transactions);
        PaymentEvent event = event(UUID.randomUUID(), PaymentEventKind.PAYMENT_SUCCEEDED);

        assertThatThrownBy(() -> processor.process(event)).isInstanceOf(IllegalStateException.class);

        // Neither the record of the event nor the handler's own write survived.
        assertThat(recorded(event.eventId())).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM processed_events WHERE consumer = 'handler-work' "
                + "AND event_id = ?", Integer.class, event.eventId())).isZero();

        assertThat(processor.process(event)).isTrue();
        assertThat(handler.handled).containsExactly(event);
        assertThat(recorded(event.eventId())).isEqualTo(1);
    }

    @Test
    void theSameEventDeliveredAtTheSameTimeIsHandledOnce() throws Exception {
        ScriptedHandler handler = new ScriptedHandler();
        PaymentEventProcessor processor = new PaymentEventProcessor(processed, handler, transactions);
        PaymentEvent event = event(UUID.randomUUID(), PaymentEventKind.PAYMENT_SUCCEEDED);
        int callers = 8;
        java.util.concurrent.ExecutorService pool = java.util.concurrent.Executors.newFixedThreadPool(callers);
        java.util.concurrent.CountDownLatch go = new java.util.concurrent.CountDownLatch(1);
        List<java.util.concurrent.Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < callers; i++) {
                futures.add(pool.submit(() -> {
                    go.await();
                    return processor.process(event);
                }));
            }
            go.countDown();
            int handledNow = 0;
            for (java.util.concurrent.Future<Boolean> future : futures) {
                if (future.get()) {
                    handledNow++;
                }
            }
            assertThat(handledNow).isEqualTo(1);
        } finally {
            pool.shutdownNow();
        }
        assertThat(handler.handled).hasSize(1);
    }
}
