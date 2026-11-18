package com.ticketflow.catalog.events;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class LayoutRepository {

    private final JdbcClient jdbc;

    LayoutRepository(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /** Removes the whole layout of an event: its seats and sections first, then its tiers. */
    void deleteLayout(UUID eventId) {
        jdbc.sql("DELETE FROM sections WHERE event_id = :event").param("event", eventId).update();
        jdbc.sql("DELETE FROM price_tiers WHERE event_id = :event").param("event", eventId).update();
    }

    void insertTier(UUID eventId, UUID id, String name, long priceMinor, int position) {
        jdbc.sql("""
                        INSERT INTO price_tiers (id, event_id, name, price_minor, position)
                        VALUES (:id, :event, :name, :price, :position)
                        """)
                .param("id", id)
                .param("event", eventId)
                .param("name", name)
                .param("price", priceMinor)
                .param("position", position)
                .update();
    }

    void insertSection(UUID eventId, UUID id, String name, int position, int rows, int seatsPerRow, UUID tierId) {
        jdbc.sql("""
                        INSERT INTO sections (id, event_id, name, position, row_count, seats_per_row, tier_id)
                        VALUES (:id, :event, :name, :position, :rows, :perRow, :tier)
                        """)
                .param("id", id)
                .param("event", eventId)
                .param("name", name)
                .param("position", position)
                .param("rows", rows)
                .param("perRow", seatsPerRow)
                .param("tier", tierId)
                .update();
    }

    /**
     * Creates one seat for every row letter (A, B, ...) and seat number of a section, each with a new random ID,
     * in a single statement.
     */
    void generateSeats(UUID eventId, UUID sectionId, int rows, int seatsPerRow) {
        jdbc.sql("""
                        INSERT INTO seats (id, event_id, section_id, row_label, seat_number)
                        SELECT gen_random_uuid(), :event, :section, chr(64 + r), n
                        FROM generate_series(1, :rows) AS r, generate_series(1, :perRow) AS n
                        """)
                .param("event", eventId)
                .param("section", sectionId)
                .param("rows", rows)
                .param("perRow", seatsPerRow)
                .update();
    }

    long countSections(UUID eventId) {
        return jdbc.sql("SELECT count(*) FROM sections WHERE event_id = :event")
                .param("event", eventId)
                .query(Long.class)
                .single();
    }

    /** The frozen layout with all its seats, or empty when the event does not exist or is still a draft. */
    Optional<PublishedLayout> findPublished(UUID eventId) {
        record Published(long version, String currency) {
        }
        Published event = jdbc.sql("""
                        SELECT version, currency FROM events WHERE id = :event AND published_at IS NOT NULL
                        """)
                .param("event", eventId)
                .query((rs, rowNum) -> new Published(rs.getLong("version"), rs.getString("currency")))
                .optional()
                .orElse(null);
        if (event == null) {
            return Optional.empty();
        }
        List<LayoutDetail.PriceTierView> tiers = jdbc.sql("""
                        SELECT id, name, price_minor FROM price_tiers WHERE event_id = :event ORDER BY position
                        """)
                .param("event", eventId)
                .query((rs, rowNum) -> new LayoutDetail.PriceTierView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price_minor")))
                .list();
        record SectionHead(UUID id, String name, UUID tierId, String tierName) {
        }
        List<SectionHead> heads = jdbc.sql("""
                        SELECT s.id, s.name, s.tier_id, t.name AS tier_name
                        FROM sections s JOIN price_tiers t ON t.id = s.tier_id
                        WHERE s.event_id = :event
                        ORDER BY s.position
                        """)
                .param("event", eventId)
                .query((rs, rowNum) -> new SectionHead(rs.getObject("id", UUID.class), rs.getString("name"),
                        rs.getObject("tier_id", UUID.class), rs.getString("tier_name")))
                .list();
        // Seats come in display order: section position, then row letter, then seat number.
        Map<UUID, Map<String, List<PublishedLayout.Seat>>> seatsBySection = new HashMap<>();
        jdbc.sql("""
                        SELECT s.section_id, s.id, s.row_label, s.seat_number
                        FROM seats s JOIN sections c ON c.id = s.section_id
                        WHERE s.event_id = :event
                        ORDER BY c.position, s.row_label, s.seat_number
                        """)
                .param("event", eventId)
                .query((rs, rowNum) -> {
                    seatsBySection
                            .computeIfAbsent(rs.getObject("section_id", UUID.class), key -> new LinkedHashMap<>())
                            .computeIfAbsent(rs.getString("row_label"), key -> new ArrayList<>())
                            .add(new PublishedLayout.Seat(rs.getObject("id", UUID.class), rs.getInt("seat_number")));
                    return rowNum;
                })
                .list();
        List<PublishedLayout.Section> sections = new ArrayList<>();
        int total = 0;
        for (SectionHead head : heads) {
            List<PublishedLayout.Row> rows = new ArrayList<>();
            for (Map.Entry<String, List<PublishedLayout.Seat>> row
                    : seatsBySection.getOrDefault(head.id(), Map.of()).entrySet()) {
                rows.add(new PublishedLayout.Row(row.getKey(), row.getValue()));
                total += row.getValue().size();
            }
            sections.add(new PublishedLayout.Section(head.id(), head.name(), head.tierId(), head.tierName(), rows));
        }
        return Optional.of(new PublishedLayout(eventId, event.version(), event.currency(), tiers, sections, total));
    }

    LayoutDetail find(UUID eventId) {
        String currency = jdbc.sql("SELECT currency FROM events WHERE id = :event")
                .param("event", eventId)
                .query((rs, rowNum) -> rs.getString("currency"))
                .optional()
                .orElse(null);
        List<LayoutDetail.PriceTierView> tiers = jdbc.sql("""
                        SELECT id, name, price_minor FROM price_tiers WHERE event_id = :event ORDER BY position
                        """)
                .param("event", eventId)
                .query((rs, rowNum) -> new LayoutDetail.PriceTierView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getLong("price_minor")))
                .list();
        List<LayoutDetail.SectionView> sections = jdbc.sql("""
                        SELECT s.id, s.name, t.name AS tier_name, s.row_count, s.seats_per_row
                        FROM sections s JOIN price_tiers t ON t.id = s.tier_id
                        WHERE s.event_id = :event
                        ORDER BY s.position
                        """)
                .param("event", eventId)
                .query((rs, rowNum) -> new LayoutDetail.SectionView(
                        rs.getObject("id", UUID.class), rs.getString("name"), rs.getString("tier_name"),
                        rs.getInt("row_count"), rs.getInt("seats_per_row"),
                        rs.getInt("row_count") * rs.getInt("seats_per_row")))
                .list();
        int total = sections.stream().mapToInt(LayoutDetail.SectionView::seatCount).sum();
        return new LayoutDetail(currency, tiers, sections, total);
    }
}
