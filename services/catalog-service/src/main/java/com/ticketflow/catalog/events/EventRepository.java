package com.ticketflow.catalog.events;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EventRepository {

    private final JdbcClient jdbc;

    EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Published events in start-time order; the id breaks ties so pages never shuffle. */
    List<EventSummary> findPublished(int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, title, starts_at
                        FROM events
                        WHERE published_at IS NOT NULL
                        ORDER BY starts_at, id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> new EventSummary(
                        rs.getObject("id", UUID.class),
                        rs.getString("title"),
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant()))
                .list();
    }

    long countPublished() {
        return jdbc.sql("SELECT count(*) FROM events WHERE published_at IS NOT NULL")
                .query(Long.class)
                .single();
    }
}
