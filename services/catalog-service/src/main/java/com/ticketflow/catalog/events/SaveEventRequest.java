package com.ticketflow.catalog.events;

import com.ticketflow.catalog.web.FieldProblem;
import com.ticketflow.catalog.web.InvalidRequestException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * The body of "create event" and "update event": the only fields an organizer may set. Ownership, status and
 * version are never read from the body (ADR-0009: bodies are bound to request objects, never to rows). The
 * venue coordinates are optional; they let visitors find the event with a "near me" search.
 */
public record SaveEventRequest(String title, Instant startsAt, String venueName, String city,
        Double venueLatitude, Double venueLongitude) {

    static final int TITLE_MAX = 200;
    static final int VENUE_MAX = 200;
    static final int CITY_MAX = 100;

    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

    /** The same values with the texts trimmed, or {@link InvalidRequestException} listing every problem. */
    SaveEventRequest validated(Instant now) {
        List<FieldProblem> problems = new ArrayList<>();
        String cleanTitle = text("title", title, TITLE_MAX, problems);
        String cleanVenue = text("venueName", venueName, VENUE_MAX, problems);
        String cleanCity = text("city", city, CITY_MAX, problems);
        if (startsAt == null) {
            problems.add(new FieldProblem("startsAt", "is required"));
        } else if (!startsAt.isAfter(now)) {
            problems.add(new FieldProblem("startsAt", "must be in the future"));
        }
        if ((venueLatitude == null) != (venueLongitude == null)) {
            problems.add(new FieldProblem(venueLatitude == null ? "venueLatitude" : "venueLongitude",
                    "is required when the other coordinate is given"));
        }
        if (venueLatitude != null && !(venueLatitude >= -90 && venueLatitude <= 90)) {
            problems.add(new FieldProblem("venueLatitude", "must be from -90 to 90"));
        }
        if (venueLongitude != null && !(venueLongitude >= -180 && venueLongitude <= 180)) {
            problems.add(new FieldProblem("venueLongitude", "must be from -180 to 180"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
        return new SaveEventRequest(cleanTitle, startsAt, cleanVenue, cleanCity, venueLatitude, venueLongitude);
    }

    /** Trims a required text and records what is wrong with it; also used for the names in a layout. */
    static String text(String field, String value, int max, List<FieldProblem> problems) {
        if (value == null) {
            problems.add(new FieldProblem(field, "is required"));
            return null;
        }
        String clean = value.strip();
        if (clean.isEmpty()) {
            problems.add(new FieldProblem(field, "must not be empty"));
        } else if (clean.codePointCount(0, clean.length()) > max) {
            problems.add(new FieldProblem(field, "must be at most " + max + " characters"));
        } else if (CONTROL_CHARACTERS.matcher(clean).find()) {
            problems.add(new FieldProblem(field, "must not contain control characters"));
        }
        return clean;
    }
}
