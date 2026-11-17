package com.ticketflow.catalog.events;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class EventRepository {

    /** Who owns an event and whether it can still be edited; what an update needs to explain a refusal. */
    record EventState(UUID ownerId, boolean published) {
    }

    private final JdbcClient jdbc;

    EventRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Published events in start-time order; the id breaks ties so pages never shuffle. */
    List<EventSummary> findPublished(int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, title, starts_at, venue_name, city
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
                        rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                        rs.getString("venue_name"),
                        rs.getString("city")))
                .list();
    }

    long countPublished() {
        return jdbc.sql("SELECT count(*) FROM events WHERE published_at IS NOT NULL")
                .query(Long.class)
                .single();
    }

    /** All events of one organizer, drafts included, in start-time order with the id as tie-breaker. */
    List<EventDetail> findByOwner(UUID ownerId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, title, starts_at, venue_name, city, published_at, version
                        FROM events
                        WHERE owner_id = :owner
                        ORDER BY starts_at, id
                        LIMIT :limit OFFSET :offset
                        """)
                .param("owner", ownerId)
                .param("limit", limit)
                .param("offset", offset)
                .query((rs, rowNum) -> detailOf(rs))
                .list();
    }

    long countByOwner(UUID ownerId) {
        return jdbc.sql("SELECT count(*) FROM events WHERE owner_id = :owner")
                .param("owner", ownerId)
                .query(Long.class)
                .single();
    }

    Optional<EventDetail> findDetail(UUID id) {
        return jdbc.sql("""
                        SELECT id, title, starts_at, venue_name, city, published_at, version
                        FROM events
                        WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> detailOf(rs))
                .optional();
    }

    Optional<EventState> findState(UUID id) {
        return jdbc.sql("SELECT owner_id, published_at IS NOT NULL AS published FROM events WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> new EventState(rs.getObject("owner_id", UUID.class), rs.getBoolean("published")))
                .optional();
    }

    /**
     * The same compare-and-set as {@link #updateDraft}, for a layout change: it only succeeds for a draft of this
     * owner that still has the version the caller saw, sets the currency, and returns the new version. The row
     * stays locked until the end of the transaction, so the layout is replaced by one writer at a time.
     */
    Optional<Long> bumpVersionForLayout(UUID id, UUID ownerId, long expectedVersion, String currency) {
        return jdbc.sql("""
                        UPDATE events
                           SET currency = :currency, updated_at = now(), version = version + 1
                         WHERE id = :id AND owner_id = :owner AND version = :version AND published_at IS NULL
                        RETURNING version
                        """)
                .param("id", id)
                .param("owner", ownerId)
                .param("version", expectedVersion)
                .param("currency", currency)
                .query(Long.class)
                .optional();
    }

    void insertDraft(UUID id, UUID ownerId, SaveEventRequest event) {
        jdbc.sql("""
                        INSERT INTO events (id, title, starts_at, venue_name, city, owner_id)
                        VALUES (:id, :title, :startsAt, :venueName, :city, :owner)
                        """)
                .param("id", id)
                .param("title", event.title())
                .param("startsAt", OffsetDateTime.ofInstant(event.startsAt(), ZoneOffset.UTC))
                .param("venueName", event.venueName())
                .param("city", event.city())
                .param("owner", ownerId)
                .update();
    }

    /**
     * Changes a draft that belongs to this owner and still has the version the caller saw. Returns false when
     * no row matched; {@link #findState} then tells why.
     */
    boolean updateDraft(UUID id, UUID ownerId, long expectedVersion, SaveEventRequest event) {
        int rows = jdbc.sql("""
                        UPDATE events
                           SET title = :title, starts_at = :startsAt, venue_name = :venueName, city = :city,
                               updated_at = now(), version = version + 1
                         WHERE id = :id AND owner_id = :owner AND version = :version AND published_at IS NULL
                        """)
                .param("id", id)
                .param("owner", ownerId)
                .param("version", expectedVersion)
                .param("title", event.title())
                .param("startsAt", OffsetDateTime.ofInstant(event.startsAt(), ZoneOffset.UTC))
                .param("venueName", event.venueName())
                .param("city", event.city())
                .update();
        return rows == 1;
    }

    private static EventDetail detailOf(java.sql.ResultSet rs) throws java.sql.SQLException {
        return new EventDetail(
                rs.getObject("id", UUID.class),
                rs.getString("title"),
                rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                rs.getString("venue_name"),
                rs.getString("city"),
                rs.getObject("published_at") == null ? "DRAFT" : "PUBLISHED",
                rs.getLong("version"));
    }
}
