package com.ticketflow.identity.location;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

/** SQL for the three location tables (ADR-0015). Plain SQL: these are rows to append, read and delete, not aggregates. */
@Repository
class LocationRepository {

    record StoredConsent(LocationLevel level, int textVersion, Instant decidedAt) {
    }

    private final JdbcClient jdbc;

    LocationRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    Optional<StoredConsent> findConsent(UUID userId) {
        return jdbc.sql("select level, text_version, decided_at from location_consents where user_id = :id")
                .param("id", userId)
                .query((rs, n) -> new StoredConsent(LocationLevel.valueOf(rs.getString("level")),
                        rs.getInt("text_version"), instant(rs.getObject("decided_at", OffsetDateTime.class))))
                .optional();
    }

    void saveConsent(UUID userId, LocationLevel level, int textVersion, Instant now) {
        jdbc.sql("""
                insert into location_consents (user_id, level, text_version, decided_at)
                values (:id, :level, :version, :now)
                on conflict (user_id) do update
                set level = excluded.level, text_version = excluded.text_version, decided_at = excluded.decided_at
                """)
                .param("id", userId).param("level", level.name()).param("version", textVersion)
                .param("now", utc(now))
                .update();
    }

    int deleteAllPositions(UUID userId) {
        return jdbc.sql("delete from location_positions where user_id = :id").param("id", userId).update();
    }

    int deletePositionsBefore(UUID userId, Instant cutoff) {
        return jdbc.sql("delete from location_positions where user_id = :id and recorded_at < :cutoff")
                .param("id", userId).param("cutoff", utc(cutoff)).update();
    }

    /** Retention for everybody: deletes the points older than the cutoff of the user's level. Idempotent. */
    int deleteExpired(LocationLevel level, Instant cutoff) {
        return jdbc.sql("""
                delete from location_positions p
                using location_consents c
                where c.user_id = p.user_id and c.level = :level and p.recorded_at < :cutoff
                """)
                .param("level", level.name()).param("cutoff", utc(cutoff)).update();
    }

    /** Points of users who are no longer tracked are removed too (a safety net: switching off already deletes them). */
    int deleteOfUntrackedUsers() {
        return jdbc.sql("""
                delete from location_positions p
                where not exists (select 1 from location_consents c
                                  where c.user_id = p.user_id and c.level in ('FULL', 'MINIMUM'))
                """).update();
    }

    Optional<Instant> newestRecordedAt(UUID userId) {
        return jdbc.sql("select max(recorded_at) from location_positions where user_id = :id")
                .param("id", userId)
                .query((rs, n) -> {
                    OffsetDateTime value = rs.getObject(1, OffsetDateTime.class);
                    return value == null ? null : value.toInstant();
                })
                .optional();
    }

    void insertPosition(UUID userId, double latitude, double longitude, double accuracy, Instant recordedAt,
            Instant now) {
        jdbc.sql("""
                insert into location_positions (id, user_id, latitude, longitude, accuracy_m, recorded_at, received_at)
                values (:id, :user, :lat, :lng, :acc, :recorded, :received)
                """)
                .param("id", UUID.randomUUID()).param("user", userId)
                .param("lat", latitude).param("lng", longitude).param("acc", accuracy)
                .param("recorded", utc(recordedAt)).param("received", utc(now))
                .update();
    }

    List<LatestPosition> latestOfEveryConsentingUser() {
        return jdbc.sql("""
                select distinct on (p.user_id) p.user_id, u.display_name, c.level,
                       p.latitude, p.longitude, p.accuracy_m, p.recorded_at
                from location_positions p
                join location_consents c on c.user_id = p.user_id and c.level in ('FULL', 'MINIMUM')
                join users u on u.id = p.user_id
                order by p.user_id, p.recorded_at desc
                """)
                .query((rs, n) -> new LatestPosition(rs.getObject("user_id", UUID.class),
                        rs.getString("display_name"), LocationLevel.valueOf(rs.getString("level")),
                        rs.getDouble("latitude"), rs.getDouble("longitude"), rs.getDouble("accuracy_m"),
                        instant(rs.getObject("recorded_at", OffsetDateTime.class))))
                .list();
    }

    Optional<String> displayNameOf(UUID userId) {
        return jdbc.sql("select display_name from users where id = :id").param("id", userId)
                .query(String.class).optional();
    }

    List<HistoryPoint> history(UUID userId, Instant from, Instant to, int limit) {
        return jdbc.sql("""
                select latitude, longitude, accuracy_m, recorded_at
                from location_positions
                where user_id = :id
                  and recorded_at >= :from
                  and recorded_at < :to
                order by recorded_at desc
                limit :limit
                """)
                .param("id", userId)
                .param("from", utc(from))
                .param("to", utc(to))
                .param("limit", limit)
                .query((rs, n) -> new HistoryPoint(rs.getDouble("latitude"), rs.getDouble("longitude"),
                        rs.getDouble("accuracy_m"), instant(rs.getObject("recorded_at", OffsetDateTime.class))))
                .list();
    }

    /** subjectUserId is null when the read covered everybody. */
    void logAccess(UUID adminId, UUID subjectUserId, String kind, Instant now) {
        String subject = subjectUserId == null ? "null" : ":subject";
        var statement = jdbc.sql("insert into location_access_log (id, admin_id, subject_user_id, kind, accessed_at) "
                + "values (:id, :admin, " + subject + ", :kind, :now)")
                .param("id", UUID.randomUUID()).param("admin", adminId)
                .param("kind", kind).param("now", utc(now));
        if (subjectUserId != null) {
            statement = statement.param("subject", subjectUserId);
        }
        statement.update();
    }

    private static OffsetDateTime utc(Instant instant) {
        return OffsetDateTime.ofInstant(instant, ZoneOffset.UTC);
    }

    private static Instant instant(OffsetDateTime value) {
        return value.toInstant();
    }
}
