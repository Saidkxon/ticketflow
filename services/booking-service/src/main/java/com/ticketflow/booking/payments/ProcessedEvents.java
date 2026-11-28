package com.ticketflow.booking.payments;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * The IDs of the events this service has handled (ADR-0006 R2). A message that the broker delivers again, or that was
 * published twice, carries the same event ID and is recognized here, so handling an event twice does nothing.
 */
@Repository
class ProcessedEvents {

    private final JdbcClient jdbc;

    ProcessedEvents(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Records the event as handled, in the caller's transaction.
     *
     * @return true when this is the first time, false when the event was recorded before
     */
    boolean markProcessed(String consumer, UUID eventId, String eventType) {
        return jdbc.sql("""
                        INSERT INTO processed_events (consumer, event_id, event_type)
                        VALUES (:consumer, :eventId, :eventType)
                        ON CONFLICT (consumer, event_id) DO NOTHING""")
                .param("consumer", consumer)
                .param("eventId", eventId)
                .param("eventType", eventType)
                .update() == 1;
    }
}
