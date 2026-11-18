package com.ticketflow.catalog.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.greaterThan;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.lessThan;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.catalog.AbstractPostgresIntegrationTest;
import com.ticketflow.catalog.security.BearerTokenFilter;
import com.ticketflow.catalog.web.CorrelationIdFilter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * Searching the public event list (FR-4) and the venue coordinates behind "near me" (ADR-0015), through the real
 * filters and on a real PostgreSQL.
 */
class EventSearchTest extends AbstractPostgresIntegrationTest {

    // Tashkent centre, a venue about 4.6 km away, and Samarkand about 270 km away.
    private static final String TASHKENT = "nearLatitude=41.3111&nearLongitude=69.2797";
    private static final String SAMARKAND = "nearLatitude=39.6542&nearLongitude=66.9597";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private BearerTokenFilter bearerTokenFilter;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;
    private String adaToken;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM events").update();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, bearerTokenFilter)
                .build();
        adaToken = tokenFor(UUID.randomUUID(), "CUSTOMER", "ORGANIZER");
    }

    /** Inserts an event straight into the table; coordinates are numbers or null (test data only). */
    private void insert(String title, String city, String startsAt, boolean published, Double lat, Double lng) {
        jdbc.sql("INSERT INTO events (id, title, starts_at, venue_name, city, owner_id, published_at, "
                        + "venue_latitude, venue_longitude) VALUES (gen_random_uuid(), :title, CAST(:startsAt AS timestamptz), "
                        + "'Hall', :city, gen_random_uuid(), "
                        + (published ? "now()" : "NULL") + ", " + (lat == null ? "NULL" : lat) + ", "
                        + (lng == null ? "NULL" : lng) + ")")
                .param("title", title).param("startsAt", startsAt).param("city", city).update();
    }

    private void standardEvents() {
        insert("Jazz Night", "Tashkent", "2099-01-10T18:00:00Z", true, 41.3111, 69.2797);
        insert("Rock Festival", "Tashkent", "2099-02-10T18:00:00Z", true, 41.35, 69.30);
        insert("Samarkand Jazz", "Samarkand", "2099-03-10T18:00:00Z", true, 39.6542, 66.9597);
        insert("100% Live", "Bukhara", "2099-04-10T18:00:00Z", true, null, null);
        insert("Hidden Draft", "Tashkent", "2099-05-10T18:00:00Z", false, 41.3111, 69.2797);
    }

    // ---- name, city, date

    @Test
    void withoutFiltersEveryPublishedEventIsListedInStartOrder() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(4))
                .andExpect(jsonPath("$.items[*].title",
                        contains("Jazz Night", "Rock Festival", "Samarkand Jazz", "100% Live")))
                .andExpect(jsonPath("$.items[0].venueLatitude").value(41.3111))
                .andExpect(jsonPath("$.items[0].venueLongitude").value(69.2797))
                .andExpect(jsonPath("$.items[0].distanceKm").value(nullValue()))
                .andExpect(jsonPath("$.items[3].venueLatitude").value(nullValue()));
    }

    @Test
    void theNameFilterIgnoresCaseAndMatchesPartOfTheTitle() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events?q=JAZZ"))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].title", contains("Jazz Night", "Samarkand Jazz")));
        mockMvc.perform(get("/api/v1/events").param("q", "ock fest"))
                .andExpect(jsonPath("$.items[*].title", contains("Rock Festival")));
        mockMvc.perform(get("/api/v1/events").param("q", "nothing like it"))
                .andExpect(jsonPath("$.items", empty()))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void percentAndUnderscoreInTheNameAreLiteralCharacters() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events").param("q", "%"))
                .andExpect(jsonPath("$.items[*].title", contains("100% Live")));
        mockMvc.perform(get("/api/v1/events").param("q", "_"))
                .andExpect(jsonPath("$.items", empty()));
        mockMvc.perform(get("/api/v1/events").param("q", "\\"))
                .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void aQuoteInTheNameIsJustText() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events").param("q", "x'; DROP TABLE events; --"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", empty()));
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(jsonPath("$.totalItems").value(4));
    }

    @Test
    void theCityFilterIgnoresCaseAndNeedsTheWholeName() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events?city=tashkent"))
                .andExpect(jsonPath("$.items[*].title", contains("Jazz Night", "Rock Festival")));
        mockMvc.perform(get("/api/v1/events?city=Tash"))
                .andExpect(jsonPath("$.items", empty()));
    }

    @Test
    void theDateWindowIncludesFromAndExcludesTo() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events?from=2099-02-10T18:00:00Z&to=2099-03-10T18:00:00Z"))
                .andExpect(jsonPath("$.items[*].title", contains("Rock Festival")));
        mockMvc.perform(get("/api/v1/events?from=2099-03-01T00:00:00Z"))
                .andExpect(jsonPath("$.items[*].title", contains("Samarkand Jazz", "100% Live")));
        mockMvc.perform(get("/api/v1/events?to=2099-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.items[*].title", contains("Jazz Night")));
    }

    @Test
    void filtersCombineAndPagingCountsOnlyTheMatches() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events?q=jazz&city=Tashkent"))
                .andExpect(jsonPath("$.items[*].title", contains("Jazz Night")));
        mockMvc.perform(get("/api/v1/events?q=jazz&size=1&page=1"))
                .andExpect(jsonPath("$.items[*].title", contains("Samarkand Jazz")))
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(1));
    }

    // ---- near me

    @Test
    void aNearSearchKeepsEventsInsideTheRadiusNearestFirstWithTheirDistance() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events?" + TASHKENT))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalItems").value(2))
                .andExpect(jsonPath("$.items[*].title", contains("Jazz Night", "Rock Festival")))
                .andExpect(jsonPath("$.items[0].distanceKm").value(0.0))
                .andExpect(jsonPath("$.items[1].distanceKm", greaterThan(4.0)))
                .andExpect(jsonPath("$.items[1].distanceKm", lessThan(5.5)));
    }

    @Test
    void theRadiusDecidesWhoIsInAndEventsWithoutCoordinatesOrDraftsNeverAre() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events?" + TASHKENT + "&radiusKm=1"))
                .andExpect(jsonPath("$.items[*].title", contains("Jazz Night")));
        mockMvc.perform(get("/api/v1/events?" + TASHKENT + "&radiusKm=500"))
                .andExpect(jsonPath("$.items[*].title", contains("Jazz Night", "Rock Festival", "Samarkand Jazz")))
                .andExpect(jsonPath("$.items[2].distanceKm", greaterThan(250.0)))
                .andExpect(jsonPath("$.items[2].distanceKm", lessThan(290.0)));
    }

    @Test
    void aNearSearchSortsByDistanceNotByDate() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events?" + SAMARKAND + "&radiusKm=500"))
                .andExpect(jsonPath("$.items[*].title", contains("Samarkand Jazz", "Jazz Night", "Rock Festival")));
    }

    @Test
    void aNearSearchCombinesWithTheOtherFilters() throws Exception {
        standardEvents();

        mockMvc.perform(get("/api/v1/events?" + TASHKENT + "&radiusKm=500&q=jazz"))
                .andExpect(jsonPath("$.items[*].title", contains("Jazz Night", "Samarkand Jazz")))
                .andExpect(jsonPath("$.totalItems").value(2));
        mockMvc.perform(get("/api/v1/events?" + TASHKENT + "&city=Tashkent&from=2099-02-01T00:00:00Z"))
                .andExpect(jsonPath("$.items[*].title", contains("Rock Festival")));
    }

    @Test
    void aNearSearchWorksAcrossTheDateLine() throws Exception {
        insert("Fiji East", "Suva", "2099-01-10T18:00:00Z", true, -17.0, 179.9);
        insert("Fiji West", "Taveuni", "2099-01-11T18:00:00Z", true, -17.0, -179.9);

        mockMvc.perform(get("/api/v1/events?nearLatitude=-17&nearLongitude=179.95&radiusKm=50"))
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[*].title", containsInAnyOrder("Fiji East", "Fiji West")));
    }

    // ---- bad parameters

    @Test
    void badSearchParametersAreRefusedWithTheirFieldNames() throws Exception {
        mockMvc.perform(get("/api/v1/events?from=tomorrow&nearLatitude=91&nearLongitude=69&radiusKm=0"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field",
                        containsInAnyOrder("from", "nearLatitude", "radiusKm")));
        mockMvc.perform(get("/api/v1/events?nearLatitude=41.3"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("nearLongitude"));
    }

    // ---- the venue coordinates an organizer sets

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request) {
        return request.header("Authorization", "Bearer " + adaToken);
    }

    private String eventJson(String title, String coordinates) {
        return "{\"title\":\"" + title + "\",\"startsAt\":\"2099-01-01T18:00:00Z\",\"venueName\":\"Hall\","
                + "\"city\":\"Tashkent\"" + coordinates + "}";
    }

    @Test
    void anOrganizerSetsChangesAndRemovesTheVenueCoordinates() throws Exception {
        String created = mockMvc.perform(withToken(post("/api/v1/events")).contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Show", ",\"venueLatitude\":41.3111,\"venueLongitude\":69.2797")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.venueLatitude").value(41.3111))
                .andExpect(jsonPath("$.venueLongitude").value(69.2797))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");

        mockMvc.perform(withToken(put("/api/v1/events/" + id)).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Show", ",\"venueLatitude\":-33.5,\"venueLongitude\":-70.6")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueLatitude").value(-33.5))
                .andExpect(jsonPath("$.venueLongitude").value(-70.6));
        mockMvc.perform(withToken(put("/api/v1/events/" + id)).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(eventJson("Show", "")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.venueLatitude").value(nullValue()))
                .andExpect(jsonPath("$.venueLongitude").value(nullValue()));
        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id)))
                .andExpect(jsonPath("$.venueLatitude").value(nullValue()));
    }

    @Test
    void coordinatesComeInPairsAndInRange() throws Exception {
        for (String bad : new String[] {",\"venueLatitude\":41.3", ",\"venueLongitude\":69.2",
                ",\"venueLatitude\":91,\"venueLongitude\":69.2", ",\"venueLatitude\":41.3,\"venueLongitude\":-181"}) {
            mockMvc.perform(withToken(post("/api/v1/events")).contentType(MediaType.APPLICATION_JSON)
                            .content(eventJson("Show", bad)))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                    .andExpect(jsonPath("$.errors[0].field").value(org.hamcrest.Matchers.startsWith("venue")));
        }
        assertThat(jdbc.sql("SELECT count(*) FROM events").query(Long.class).single()).isZero();
    }

    @Test
    void aPublishedEventKeepsItsCoordinatesInThePublicList() throws Exception {
        String created = mockMvc.perform(withToken(post("/api/v1/events")).contentType(MediaType.APPLICATION_JSON)
                        .content(eventJson("Show", ",\"venueLatitude\":41.3111,\"venueLongitude\":69.2797")))
                .andReturn().getResponse().getContentAsString();
        String id = JsonPath.read(created, "$.id");
        mockMvc.perform(withToken(put("/api/v1/events/" + id + "/layout")).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currency\":\"UZS\",\"priceTiers\":[{\"name\":\"Standard\",\"priceMinor\":1000}],"
                                + "\"sections\":[{\"name\":\"Floor\",\"tier\":\"Standard\",\"rows\":1,\"seatsPerRow\":2}]}"))
                .andExpect(status().isOk());
        mockMvc.perform(withToken(post("/api/v1/events/" + id + "/publish")).header("If-Match", "\"1\""))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/events?" + TASHKENT))
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(id))
                .andExpect(jsonPath("$.items[0].distanceKm").value(0.0));
    }

    @Test
    void theDatabaseRefusesHalfACoordinatePairAndOutOfRangeValues() {
        assertThatThrownBy(() -> insert("Bad", "Tashkent", "2099-01-01T00:00:00Z", true, 41.3, null))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("Bad", "Tashkent", "2099-01-01T00:00:00Z", true, 95.0, 10.0))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert("Bad", "Tashkent", "2099-01-01T00:00:00Z", true, 10.0, 181.0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
