package com.ticketflow.catalog.events;

import com.ticketflow.catalog.web.FieldProblem;
import com.ticketflow.catalog.web.InvalidRequestException;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * What a visitor can filter the public event list by (FR-4): part of the title, the city, a start-time window and
 * a distance from a point. Every part is optional. The point of a "near" search comes from the visitor's device
 * and is used for this one request only: it is never stored and never logged (ADR-0015, rules L4 and L8).
 *
 * <p>All the texts are checked here, and the repository passes them to the database as parameters, so none of
 * them can change the SQL.
 */
record EventSearch(String text, String city, Instant from, Instant to, Near near) {

    static final int TEXT_MAX = 100;
    static final double DEFAULT_RADIUS_KM = 50;
    static final double MAX_RADIUS_KM = 500;

    private static final Pattern COORDINATE = Pattern.compile("^-?\\d{1,3}(\\.\\d{1,15})?$");
    private static final Pattern RADIUS = Pattern.compile("^\\d{1,3}(\\.\\d{1,3})?$");
    private static final Pattern CONTROL_CHARACTERS = Pattern.compile("\\p{Cntrl}");

    /** The point to measure from and how far to look, in kilometres. */
    record Near(double latitude, double longitude, double radiusKm) {
    }

    static final EventSearch NONE = new EventSearch(null, null, null, null, null);

    /** Reads the query parameters, or throws {@link InvalidRequestException} listing every problem. */
    static EventSearch parse(String q, String city, String from, String to,
            String nearLatitude, String nearLongitude, String radiusKm) {
        List<FieldProblem> problems = new ArrayList<>();
        String text = optionalText("q", q, problems);
        String cleanCity = optionalText("city", city, problems);
        Instant fromInstant = instant("from", from, problems);
        Instant toInstant = instant("to", to, problems);
        if (fromInstant != null && toInstant != null && !toInstant.isAfter(fromInstant)) {
            problems.add(new FieldProblem("to", "must be after from"));
        }
        Near near = near(nearLatitude, nearLongitude, radiusKm, problems);
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
        return new EventSearch(text, cleanCity, fromInstant, toInstant, near);
    }

    private static Near near(String latitude, String longitude, String radius, List<FieldProblem> problems) {
        boolean hasLatitude = isPresent(latitude);
        boolean hasLongitude = isPresent(longitude);
        if (!hasLatitude && !hasLongitude) {
            if (isPresent(radius)) {
                problems.add(new FieldProblem("radiusKm", "needs nearLatitude and nearLongitude"));
            }
            return null;
        }
        Double lat = hasLatitude ? coordinate("nearLatitude", latitude, 90, problems) : null;
        Double lng = hasLongitude ? coordinate("nearLongitude", longitude, 180, problems) : null;
        if (!hasLatitude) {
            problems.add(new FieldProblem("nearLatitude", "is required with nearLongitude"));
        }
        if (!hasLongitude) {
            problems.add(new FieldProblem("nearLongitude", "is required with nearLatitude"));
        }
        double radiusValue = DEFAULT_RADIUS_KM;
        if (isPresent(radius)) {
            String clean = radius.strip();
            if (!RADIUS.matcher(clean).matches()
                    || Double.parseDouble(clean) < 1 || Double.parseDouble(clean) > MAX_RADIUS_KM) {
                problems.add(new FieldProblem("radiusKm", "must be a number from 1 to " + (int) MAX_RADIUS_KM));
            } else {
                radiusValue = Double.parseDouble(clean);
            }
        }
        return lat == null || lng == null ? null : new Near(lat, lng, radiusValue);
    }

    private static Double coordinate(String field, String value, double limit, List<FieldProblem> problems) {
        String clean = value.strip();
        if (!COORDINATE.matcher(clean).matches() || Math.abs(Double.parseDouble(clean)) > limit) {
            problems.add(new FieldProblem(field, "must be a number from -" + (int) limit + " to " + (int) limit));
            return null;
        }
        return Double.parseDouble(clean);
    }

    private static Instant instant(String field, String value, List<FieldProblem> problems) {
        if (!isPresent(value)) {
            return null;
        }
        try {
            return Instant.parse(value.strip());
        } catch (DateTimeParseException e) {
            problems.add(new FieldProblem(field, "must be an ISO 8601 time in UTC such as 2026-12-01T00:00:00Z"));
            return null;
        }
    }

    /** A blank value means "no filter", so a search box that was left empty is simply ignored. */
    private static String optionalText(String field, String value, List<FieldProblem> problems) {
        if (!isPresent(value)) {
            return null;
        }
        String clean = value.strip();
        if (clean.codePointCount(0, clean.length()) > TEXT_MAX) {
            problems.add(new FieldProblem(field, "must be at most " + TEXT_MAX + " characters"));
            return null;
        }
        if (CONTROL_CHARACTERS.matcher(clean).find()) {
            problems.add(new FieldProblem(field, "must not contain control characters"));
            return null;
        }
        return clean;
    }

    private static boolean isPresent(String value) {
        return value != null && !value.isBlank();
    }
}
