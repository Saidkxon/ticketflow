package com.ticketflow.catalog.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ticketflow.catalog.web.FieldProblem;
import com.ticketflow.catalog.web.InvalidRequestException;
import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Reading the search parameters of the public event list (FR-4), without a database. */
class EventSearchRequestTest {

    private static EventSearch parse(String q, String city, String from, String to, String lat, String lng,
            String radius) {
        return EventSearch.parse(q, city, from, to, lat, lng, radius);
    }

    private static List<String> problemFields(Runnable call) {
        try {
            call.run();
        } catch (InvalidRequestException e) {
            return e.errors().stream().map(FieldProblem::field).toList();
        }
        throw new AssertionError("expected InvalidRequestException");
    }

    @Test
    void noParametersMeansNoFilter() {
        EventSearch search = parse(null, null, null, null, null, null, null);

        assertThat(search).isEqualTo(EventSearch.NONE);
    }

    @Test
    void blankValuesAreIgnoredAndTextsAreTrimmed() {
        EventSearch search = parse("  jazz ", " ", "", null, null, null, "");

        assertThat(search.text()).isEqualTo("jazz");
        assertThat(search.city()).isNull();
        assertThat(search.from()).isNull();
        assertThat(search.near()).isNull();
    }

    @Test
    void aTimeWindowIsParsedAndMustBeInOrder() {
        EventSearch search = parse(null, null, "2026-12-01T00:00:00Z", "2026-12-31T00:00:00Z", null, null, null);

        assertThat(search.from()).isEqualTo(Instant.parse("2026-12-01T00:00:00Z"));
        assertThat(search.to()).isEqualTo(Instant.parse("2026-12-31T00:00:00Z"));
        assertThat(problemFields(() -> parse(null, null, "2026-12-31T00:00:00Z", "2026-12-01T00:00:00Z", null, null, null)))
                .containsExactly("to");
        assertThat(problemFields(() -> parse(null, null, "tomorrow", "2026-12-01", null, null, null)))
                .containsExactly("from", "to");
    }

    @Test
    void aNearSearchHasADefaultRadiusOf50Kilometres() {
        EventSearch search = parse(null, null, null, null, "41.31", "69.28", null);

        assertThat(search.near()).isEqualTo(new EventSearch.Near(41.31, 69.28, 50));
    }

    @Test
    void theRadiusIsCheckedAndNeedsAPoint() {
        assertThat(parse(null, null, null, null, "41.31", "69.28", "25.5").near().radiusKm()).isEqualTo(25.5);
        assertThat(problemFields(() -> parse(null, null, null, null, "41.31", "69.28", "0"))).containsExactly("radiusKm");
        assertThat(problemFields(() -> parse(null, null, null, null, "41.31", "69.28", "501"))).containsExactly("radiusKm");
        assertThat(problemFields(() -> parse(null, null, null, null, "41.31", "69.28", "far"))).containsExactly("radiusKm");
        assertThat(problemFields(() -> parse(null, null, null, null, null, null, "10"))).containsExactly("radiusKm");
    }

    @Test
    void thePointNeedsBothCoordinatesInRange() {
        assertThat(problemFields(() -> parse(null, null, null, null, "41.31", null, null)))
                .containsExactly("nearLongitude");
        assertThat(problemFields(() -> parse(null, null, null, null, null, "69.28", null)))
                .containsExactly("nearLatitude");
        assertThat(problemFields(() -> parse(null, null, null, null, "91", "69.28", null)))
                .containsExactly("nearLatitude");
        assertThat(problemFields(() -> parse(null, null, null, null, "41.31", "-180.5", null)))
                .containsExactly("nearLongitude");
        assertThat(parse(null, null, null, null, "-90", "180", null).near().latitude()).isEqualTo(-90);
    }

    @Test
    void coordinatesThatAreNotPlainNumbersAreRefused() {
        for (String bad : new String[] {"NaN", "Infinity", "1e2", "0x10", "41,31", "--1", "4 1"}) {
            assertThat(problemFields(() -> parse(null, null, null, null, bad, "69.28", null)))
                    .as(bad).containsExactly("nearLatitude");
        }
    }

    @Test
    void tooLongTextsAndControlCharactersAreRefused() {
        assertThat(problemFields(() -> parse("x".repeat(101), null, null, null, null, null, null)))
                .containsExactly("q");
        assertThat(problemFields(() -> parse(null, "Tash\nkent", null, null, null, null, null)))
                .containsExactly("city");
        assertThatThrownBy(() -> parse("x".repeat(101), "bad\u0000", null, null, null, null, null))
                .isInstanceOf(InvalidRequestException.class);
    }
}
