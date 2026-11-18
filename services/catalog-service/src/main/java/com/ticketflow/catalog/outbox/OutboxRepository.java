package com.ticketflow.catalog.outbox;

import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes events to the transactional outbox (ADR-0003, rule 1; ADR-0005). The caller runs this in the same
 * transaction as the business change, so the event cannot be lost and cannot exist without the change.
 * The relay that publishes the rows to the broker arrives in a later pull request.
 */
@Repository
public class OutboxRepository {

    private final JdbcClient jdbc;

    OutboxRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param id the ID of the message; it is the {@code eventId} inside the envelope, which consumers use to
     *           recognize duplicates (ADR-0009, part F)
     * @param payloadJson the whole envelope as JSON text
     */
    public void insert(UUID id, String aggregateType, UUID aggregateId, String eventType, String payloadJson) {
        jdbc.sql("""
                        INSERT INTO outbox_events (id, aggregate_type, aggregate_id, event_type, payload)
                        VALUES (:id, :aggregateType, :aggregateId, :eventType, CAST(:payload AS jsonb))
                        """)
                .param("id", id)
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("eventType", eventType)
                .param("payload", payloadJson)
                .update();
    }
}
