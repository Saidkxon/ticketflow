package com.ticketflow.catalog.events;

import com.ticketflow.catalog.web.FieldProblem;
import com.ticketflow.catalog.web.InvalidRequestException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * The venue layout an organizer defines for a draft event (FR-3): price tiers and sections. A section is a block
 * of lettered rows (A, B, C, ...) with the same number of seats in each row and one price tier. The seats
 * themselves are generated from this description, with an opaque ID each (ADR-0012).
 *
 * <p>Saving a layout replaces the previous one as a whole. Limits keep a venue within what the seat map and
 * Booking are sized for (NFR-10: about 2,000 seats).
 */
public record LayoutRequest(String currency, List<PriceTier> priceTiers, List<Section> sections) {

    /** A named price in the smallest unit of the event currency (ADR-0005: never floating point). */
    public record PriceTier(String name, Long priceMinor) {
    }

    /** {@code tier} is the name of one of the price tiers. */
    public record Section(String name, String tier, Integer rows, Integer seatsPerRow) {
    }

    static final Set<String> CURRENCIES = Set.of("UZS", "USD", "EUR");
    static final int MAX_TIERS = 10;
    static final int MAX_SECTIONS = 30;
    static final int MAX_ROWS = 26;
    static final int MAX_SEATS_PER_ROW = 100;
    static final int MAX_SEATS = 5000;
    static final long MAX_PRICE_MINOR = 1_000_000_000_000L;
    static final int NAME_MAX = 50;

    /** The same layout with the names trimmed, or {@link InvalidRequestException} listing every problem. */
    LayoutRequest validated() {
        List<FieldProblem> problems = new ArrayList<>();

        String cleanCurrency = null;
        if (currency == null) {
            problems.add(new FieldProblem("currency", "is required"));
        } else if (!CURRENCIES.contains(currency)) {
            problems.add(new FieldProblem("currency", "must be one of UZS, USD, EUR"));
        } else {
            cleanCurrency = currency;
        }

        Map<String, String> tierNames = new HashMap<>();
        List<PriceTier> cleanTiers = new ArrayList<>();
        if (priceTiers == null || priceTiers.isEmpty()) {
            problems.add(new FieldProblem("priceTiers", "must have at least one tier"));
        } else if (priceTiers.size() > MAX_TIERS) {
            problems.add(new FieldProblem("priceTiers", "must have at most " + MAX_TIERS + " tiers"));
        } else {
            for (int i = 0; i < priceTiers.size(); i++) {
                PriceTier tier = priceTiers.get(i);
                String field = "priceTiers[" + i + "]";
                if (tier == null) {
                    problems.add(new FieldProblem(field, "is required"));
                    continue;
                }
                String name = SaveEventRequest.text(field + ".name", tier.name(), NAME_MAX, problems);
                if (tier.priceMinor() == null) {
                    problems.add(new FieldProblem(field + ".priceMinor", "is required"));
                } else if (tier.priceMinor() < 1 || tier.priceMinor() > MAX_PRICE_MINOR) {
                    problems.add(new FieldProblem(field + ".priceMinor",
                            "must be between 1 and " + MAX_PRICE_MINOR));
                }
                if (name != null && !name.isEmpty() && tierNames.put(keyOf(name), name) != null) {
                    problems.add(new FieldProblem(field + ".name", "must be unique"));
                }
                cleanTiers.add(new PriceTier(name, tier.priceMinor()));
            }
        }

        Map<String, String> sectionNames = new HashMap<>();
        List<Section> cleanSections = new ArrayList<>();
        long totalSeats = 0;
        if (sections == null || sections.isEmpty()) {
            problems.add(new FieldProblem("sections", "must have at least one section"));
        } else if (sections.size() > MAX_SECTIONS) {
            problems.add(new FieldProblem("sections", "must have at most " + MAX_SECTIONS + " sections"));
        } else {
            for (int i = 0; i < sections.size(); i++) {
                Section section = sections.get(i);
                String field = "sections[" + i + "]";
                if (section == null) {
                    problems.add(new FieldProblem(field, "is required"));
                    continue;
                }
                String name = SaveEventRequest.text(field + ".name", section.name(), NAME_MAX, problems);
                if (name != null && !name.isEmpty() && sectionNames.put(keyOf(name), name) != null) {
                    problems.add(new FieldProblem(field + ".name", "must be unique"));
                }
                String tier = SaveEventRequest.text(field + ".tier", section.tier(), NAME_MAX, problems);
                String knownTier = tier == null ? null : tierNames.get(keyOf(tier));
                if (tier != null && !tier.isEmpty() && knownTier == null) {
                    problems.add(new FieldProblem(field + ".tier", "must be the name of one of the price tiers"));
                }
                boolean rowsOk = inRange(field + ".rows", section.rows(), MAX_ROWS, problems);
                boolean perRowOk = inRange(field + ".seatsPerRow", section.seatsPerRow(), MAX_SEATS_PER_ROW, problems);
                if (rowsOk && perRowOk) {
                    totalSeats += (long) section.rows() * section.seatsPerRow();
                }
                cleanSections.add(new Section(name, knownTier == null ? tier : knownTier, section.rows(),
                        section.seatsPerRow()));
            }
            if (totalSeats > MAX_SEATS) {
                problems.add(new FieldProblem("sections", "must have at most " + MAX_SEATS + " seats in total"));
            }
        }

        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
        return new LayoutRequest(cleanCurrency, cleanTiers, cleanSections);
    }

    private static boolean inRange(String field, Integer value, int max, List<FieldProblem> problems) {
        if (value == null) {
            problems.add(new FieldProblem(field, "is required"));
            return false;
        }
        if (value < 1 || value > max) {
            problems.add(new FieldProblem(field, "must be between 1 and " + max));
            return false;
        }
        return true;
    }

    /** Names are compared without regard to case, so "VIP" and "vip" cannot both exist. */
    private static String keyOf(String name) {
        return name.toLowerCase(Locale.ROOT);
    }
}
