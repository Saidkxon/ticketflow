package com.ticketflow.catalog.events;

import java.util.List;
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
