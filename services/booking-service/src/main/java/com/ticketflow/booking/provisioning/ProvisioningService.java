package com.ticketflow.booking.provisioning;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Builds the seat inventory of an event from Catalog's immutable layout (ADR-0012, part A, rules 3 and 4).
 *
 * <p>It is safe to run again and to run at the same time for the same event (duplicate messages, several Booking
 * copies): the event row is inserted first, and whoever does not insert it does nothing. The event row and all of its
 * seats are written in one transaction, so an event is on sale completely or not at all.
 */
@Component
public class ProvisioningService {

    /** What {@link #provision} did. */
    public enum Result {
        PROVISIONED, ALREADY_PROVISIONED
    }

    /** A sanity limit well above what Catalog allows (5,000 seats). */
    static final int MAX_SEATS = 10_000;

    private static final Logger log = LoggerFactory.getLogger(ProvisioningService.class);
    private static final Pattern CURRENCY = Pattern.compile("^[A-Z]{3}$");
    private static final String INSERT_EVENT =
            "INSERT INTO event_inventory (event_id, layout_version, currency, total_seats) VALUES (?, ?, ?, ?) "
                    + "ON CONFLICT (event_id) DO NOTHING";
    private static final String INSERT_SEAT =
            "INSERT INTO event_seats (event_id, seat_id, section_name, row_label, seat_number, tier_name, price_minor) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?) ON CONFLICT (event_id, seat_id) DO NOTHING";

    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    ProvisioningService(JdbcTemplate jdbc, PlatformTransactionManager transactions) {
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    /**
     * Creates the inventory for {@code layout}, which must be the layout of {@code eventId} at {@code layoutVersion}.
     *
     * @throws InvalidMessageException when the layout is not what the message promised or is not consistent
     */
    public Result provision(UUID eventId, long layoutVersion, CatalogLayout layout) {
        List<Object[]> seats = seatRows(eventId, layoutVersion, layout);
        Result result = transaction.execute(status -> {
            int inserted = jdbc.update(INSERT_EVENT, eventId, layoutVersion, layout.currency(), seats.size());
            if (inserted == 0) {
                return Result.ALREADY_PROVISIONED;
            }
            jdbc.batchUpdate(INSERT_SEAT, seats);
            return Result.PROVISIONED;
        });
        if (result == Result.PROVISIONED) {
            log.info("event {} is on sale: {} seats from layout version {}", eventId, seats.size(), layoutVersion);
        } else {
            log.info("event {} was already provisioned; nothing to do", eventId);
        }
        return result;
    }

    /** Checks the layout and turns it into one row of statement parameters per seat. */
    static List<Object[]> seatRows(UUID eventId, long layoutVersion, CatalogLayout layout) {
        if (layout == null || !eventId.equals(layout.eventId())) {
            throw new InvalidMessageException("the layout is for another event");
        }
        if (layout.layoutVersion() == null || layout.layoutVersion() != layoutVersion) {
            throw new InvalidMessageException("the layout version differs from the one in the message");
        }
        if (layout.currency() == null || !CURRENCY.matcher(layout.currency()).matches()) {
            throw new InvalidMessageException("the currency is not a three-letter code");
        }
        Map<UUID, CatalogLayout.PriceTier> tiers = tiersById(layout);
        if (layout.sections() == null || layout.sections().isEmpty()) {
            throw new InvalidMessageException("the layout has no sections");
        }
        List<Object[]> rows = new ArrayList<>();
        Set<UUID> seatIds = new HashSet<>();
        for (CatalogLayout.Section section : layout.sections()) {
            CatalogLayout.PriceTier tier = section == null || section.tierId() == null
                    ? null : tiers.get(section.tierId());
            if (tier == null || section.name() == null || section.rows() == null) {
                throw new InvalidMessageException("a section is incomplete or names an unknown price tier");
            }
            for (CatalogLayout.Row row : section.rows()) {
                if (row == null || row.label() == null || row.seats() == null) {
                    throw new InvalidMessageException("a row is incomplete");
                }
                for (CatalogLayout.Seat seat : row.seats()) {
                    if (seat == null || seat.id() == null || seat.number() == null || seat.number() < 1) {
                        throw new InvalidMessageException("a seat is incomplete");
                    }
                    if (!seatIds.add(seat.id())) {
                        throw new InvalidMessageException("a seat ID appears twice");
                    }
                    if (rows.size() >= MAX_SEATS) {
                        throw new InvalidMessageException("the layout has more seats than allowed");
                    }
                    rows.add(new Object[] {eventId, seat.id(), section.name(), row.label(), seat.number(),
                            tier.name(), tier.priceMinor()});
                }
            }
        }
        if (rows.isEmpty()) {
            throw new InvalidMessageException("the layout has no seats");
        }
        if (layout.totalSeats() == null || layout.totalSeats() != rows.size()) {
            throw new InvalidMessageException("totalSeats does not match the seats in the layout");
        }
        return rows;
    }

    private static Map<UUID, CatalogLayout.PriceTier> tiersById(CatalogLayout layout) {
        if (layout.priceTiers() == null || layout.priceTiers().isEmpty()) {
            throw new InvalidMessageException("the layout has no price tiers");
        }
        Map<UUID, CatalogLayout.PriceTier> tiers = new HashMap<>();
        for (CatalogLayout.PriceTier tier : layout.priceTiers()) {
            if (tier == null || tier.id() == null || tier.name() == null || tier.priceMinor() == null
                    || tier.priceMinor() <= 0) {
                throw new InvalidMessageException("a price tier is incomplete");
            }
            if (tiers.put(tier.id(), tier) != null) {
                throw new InvalidMessageException("a price tier ID appears twice");
            }
        }
        return tiers;
    }
}
