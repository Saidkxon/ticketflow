package com.ticketflow.payment.outbox;

import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/**
 * Writes events to the transactional outbox (ADR-0003, rule 1; ADR-0005). The caller runs this in the same
 * transaction as the business change, so the event cannot be lost and cannot exist without the change.
 * {@link OutboxRelay} publishes the rows to the broker.
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

    /**
     * Takes up to {@code limit} unpublished rows, oldest first, and locks them until the transaction ends. Rows that another
     * relay has already locked are skipped, so two relays never publish the same row at the same time. Must run inside a
     * transaction.
     */
    List<PendingMessage> lockUnpublished(int limit) {
        return jdbc.sql("""
                        SELECT id, event_type, aggregate_id, CAST(payload AS text) AS payload,
                               payload ->> 'correlationId' AS correlation_id
                        FROM outbox_events
                        WHERE published_at IS NULL
                        ORDER BY created_at, id
                        LIMIT :limit
                        FOR UPDATE SKIP LOCKED
                        """)
                .param("limit", limit)
                .query((rs, rowNumber) -> new PendingMessage(
                        rs.getObject("id", UUID.class),
                        rs.getString("event_type"),
                        rs.getObject("aggregate_id", UUID.class),
                        rs.getString("payload"),
                        rs.getString("correlation_id")))
                .list();
    }

    void markPublished(UUID id) {
        jdbc.sql("UPDATE outbox_events SET published_at = now() WHERE id = :id")
                .param("id", id)
                .update();
    }
}
