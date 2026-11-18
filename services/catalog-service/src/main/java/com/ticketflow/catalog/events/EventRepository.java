package com.ticketflow.catalog.events;

import java.sql.Types;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.SqlParameterValue;
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

    /**
     * Great-circle distance in kilometres (haversine, earth radius 6371 km) from the point :nearLat, :nearLng to
     * the venue. The sine of a half difference is periodic, so a search near longitude 180 also finds venues on
     * the other side of the date line.
     */
    private static final String DISTANCE_KM = """
            6371.0 * 2 * asin(sqrt(least(1.0,
                power(sin(radians(venue_latitude - :nearLat) / 2), 2)
                + cos(radians(:nearLat)) * cos(radians(venue_latitude))
                  * power(sin(radians(venue_longitude - :nearLng) / 2), 2))))""";

    /**
     * Published events that match the search, in start-time order with the id as tie-breaker so pages never
     * shuffle. A "near" search keeps only events with coordinates inside the radius and sorts them by distance
     * first. The SQL is assembled from fixed pieces only; every value from the caller is a parameter.
     */
    List<EventSummary> search(EventSearch search, int limit, long offset) {
        Map<String, Object> params = new HashMap<>();
        String filter = filters(search, params);
        boolean near = search.near() != null;
        params.put("limit", limit);
        params.put("offset", offset);
        return jdbc.sql("SELECT * FROM (SELECT id, title, starts_at, venue_name, city, venue_latitude, "
                        + "venue_longitude, " + (near ? DISTANCE_KM : "CAST(NULL AS double precision)")
                        + " AS distance_km FROM events WHERE published_at IS NOT NULL" + filter + ") e"
                        + (near ? " WHERE distance_km <= :radius ORDER BY distance_km, starts_at, id"
                                : " ORDER BY starts_at, id")
                        + " LIMIT :limit OFFSET :offset")
                .params(params)
                .query((rs, rowNum) -> {
                    Double distance = rs.getObject("distance_km", Double.class);
                    return new EventSummary(
                            rs.getObject("id", UUID.class),
                            rs.getString("title"),
                            rs.getObject("starts_at", OffsetDateTime.class).toInstant(),
                            rs.getString("venue_name"),
                            rs.getString("city"),
                            rs.getObject("venue_latitude", Double.class),
                            rs.getObject("venue_longitude", Double.class),
                            distance == null ? null : Math.round(distance * 10) / 10.0);
                })
                .list();
    }

    long countMatching(EventSearch search) {
        Map<String, Object> params = new HashMap<>();
        String filter = filters(search, params);
        boolean near = search.near() != null;
        return jdbc.sql("SELECT count(*) FROM (SELECT " + (near ? DISTANCE_KM : "CAST(NULL AS double precision)")
                        + " AS distance_km FROM events WHERE published_at IS NOT NULL" + filter + ") e"
                        + (near ? " WHERE distance_km <= :radius" : ""))
                .params(params)
                .query(Long.class)
                .single();
    }

    /** The WHERE parts of a search (each starting with AND) and their parameters. */
    private static String filters(EventSearch search, Map<String, Object> params) {
        StringBuilder sql = new StringBuilder();
        if (search.text() != null) {
            sql.append(" AND title ILIKE :text ESCAPE '\\'");
            params.put("text", "%" + search.text().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%");
        }
        if (search.city() != null) {
            sql.append(" AND lower(city) = lower(:city)");
            params.put("city", search.city());
        }
        if (search.from() != null) {
            sql.append(" AND starts_at >= :from");
            params.put("from", OffsetDateTime.ofInstant(search.from(), ZoneOffset.UTC));
        }
        if (search.to() != null) {
            sql.append(" AND starts_at < :to");
            params.put("to", OffsetDateTime.ofInstant(search.to(), ZoneOffset.UTC));
        }
        if (search.near() != null) {
            sql.append(" AND venue_latitude IS NOT NULL");
            params.put("nearLat", search.near().latitude());
            params.put("nearLng", search.near().longitude());
            params.put("radius", search.near().radiusKm());
        }
        return sql.toString();
    }

    /** All events of one organizer, drafts included, in start-time order with the id as tie-breaker. */
    List<EventDetail> findByOwner(UUID ownerId, int limit, long offset) {
        return jdbc.sql("""
                        SELECT id, title, starts_at, venue_name, city, venue_latitude, venue_longitude, published_at, version
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
                        SELECT id, title, starts_at, venue_name, city, venue_latitude, venue_longitude, published_at, version
                        FROM events
                        WHERE id = :id
                        """)
                .param("id", id)
                .query((rs, rowNum) -> detailOf(rs))
                .optional();
    }

    Optional<java.time.Instant> findStartsAt(UUID id) {
        return jdbc.sql("SELECT starts_at FROM events WHERE id = :id")
                .param("id", id)
                .query((rs, rowNum) -> rs.getObject("starts_at", OffsetDateTime.class).toInstant())
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

    /**
     * Publishes a draft of this owner that still has the version the caller saw, and returns the new version.
     * Like {@link #updateDraft} this is a compare-and-set, and it keeps the row locked until the end of the
     * transaction, so nothing can change the layout while the publishing is checked and recorded.
     */
    Optional<Long> publishDraft(UUID id, UUID ownerId, long expectedVersion) {
        return jdbc.sql("""
                        UPDATE events
                           SET published_at = now(), updated_at = now(), version = version + 1
                         WHERE id = :id AND owner_id = :owner AND version = :version AND published_at IS NULL
                        RETURNING version
                        """)
                .param("id", id)
                .param("owner", ownerId)
                .param("version", expectedVersion)
                .query(Long.class)
                .optional();
    }

    void insertDraft(UUID id, UUID ownerId, SaveEventRequest event) {
        jdbc.sql("""
                        INSERT INTO events (id, title, starts_at, venue_name, city, owner_id,
                                            venue_latitude, venue_longitude)
                        VALUES (:id, :title, :startsAt, :venueName, :city, :owner, :venueLatitude, :venueLongitude)
                        """)
                .param("id", id)
                .param("title", event.title())
                .param("startsAt", OffsetDateTime.ofInstant(event.startsAt(), ZoneOffset.UTC))
                .param("venueName", event.venueName())
                .param("city", event.city())
                .param("owner", ownerId)
                .param("venueLatitude", new SqlParameterValue(Types.DOUBLE, event.venueLatitude()))
                .param("venueLongitude", new SqlParameterValue(Types.DOUBLE, event.venueLongitude()))
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
                               venue_latitude = :venueLatitude, venue_longitude = :venueLongitude,
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
                .param("venueLatitude", new SqlParameterValue(Types.DOUBLE, event.venueLatitude()))
                .param("venueLongitude", new SqlParameterValue(Types.DOUBLE, event.venueLongitude()))
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
                rs.getObject("venue_latitude", Double.class),
                rs.getObject("venue_longitude", Double.class),
                rs.getObject("published_at") == null ? "DRAFT" : "PUBLISHED",
                rs.getLong("version"));
    }
}
