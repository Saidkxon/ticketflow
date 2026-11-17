package com.ticketflow.catalog.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.empty;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.jayway.jsonpath.JsonPath;
import com.ticketflow.catalog.AbstractPostgresIntegrationTest;
import com.ticketflow.catalog.security.BearerTokenFilter;
import com.ticketflow.catalog.web.CorrelationIdFilter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The venue layout of a draft event (FR-3, ADR-0012) through the real filters and on a real PostgreSQL: seats are
 * generated with their own IDs, a save replaces the whole layout, and the same ownership, version and draft
 * rules apply as for editing the event itself.
 */
class VenueLayoutTest extends AbstractPostgresIntegrationTest {

    private static final String FUTURE = "2099-01-01T18:00:00Z";

    private static final String TWO_SECTIONS = """
            {"currency":"UZS",
             "priceTiers":[{"name":"Standard","priceMinor":150000},{"name":"VIP","priceMinor":400000}],
             "sections":[{"name":"Stalls","tier":"Standard","rows":3,"seatsPerRow":4},
                         {"name":"Front","tier":"VIP","rows":2,"seatsPerRow":5}]}""";

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
    private String graceToken;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM events").update();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, bearerTokenFilter)
                .build();
        adaToken = tokenFor(UUID.randomUUID(), "CUSTOMER", "ORGANIZER");
        graceToken = tokenFor(UUID.randomUUID(), "CUSTOMER", "ORGANIZER");
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String createEvent(String token) throws Exception {
        String response = mockMvc.perform(withToken(post("/api/v1/events"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Show\",\"startsAt\":\"" + FUTURE
                                + "\",\"venueName\":\"Hall\",\"city\":\"Tashkent\"}"))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private MockHttpServletRequestBuilder saveLayout(String eventId, String token, String ifMatch, String json) {
        MockHttpServletRequestBuilder request = withToken(put("/api/v1/events/" + eventId + "/layout"), token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private long seatCount(String eventId) {
        return jdbc.sql("SELECT count(*) FROM seats WHERE event_id = :id")
                .param("id", UUID.fromString(eventId)).query(Long.class).single();
    }

    private void publish(String eventId) {
        jdbc.sql("UPDATE events SET published_at = now() WHERE id = :id")
                .param("id", UUID.fromString(eventId)).update();
    }

    // ---- saving

    @Test
    void savingALayoutGeneratesOneSeatForEveryRowLetterAndNumber() throws Exception {
        String id = createEvent(adaToken);

        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andExpect(jsonPath("$.totalSeats").value(22))
                .andExpect(jsonPath("$.priceTiers[0].name").value("Standard"))
                .andExpect(jsonPath("$.priceTiers[0].priceMinor").value(150000))
                .andExpect(jsonPath("$.priceTiers[0].id").isString())
                .andExpect(jsonPath("$.priceTiers[1].name").value("VIP"))
                .andExpect(jsonPath("$.sections[0].name").value("Stalls"))
                .andExpect(jsonPath("$.sections[0].tier").value("Standard"))
                .andExpect(jsonPath("$.sections[0].rows").value(3))
                .andExpect(jsonPath("$.sections[0].seatsPerRow").value(4))
                .andExpect(jsonPath("$.sections[0].seatCount").value(12))
                .andExpect(jsonPath("$.sections[1].name").value("Front"))
                .andExpect(jsonPath("$.sections[1].seatCount").value(10));

        assertThat(seatCount(id)).isEqualTo(22);
        UUID eventId = UUID.fromString(id);
        assertThat(jdbc.sql("SELECT count(DISTINCT id) FROM seats WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single()).isEqualTo(22);
        List<String> rowLabels = jdbc.sql("""
                        SELECT DISTINCT s.row_label FROM seats s JOIN sections c ON c.id = s.section_id
                        WHERE c.event_id = :id AND c.name = 'Stalls' ORDER BY s.row_label
                        """)
                .param("id", eventId).query(String.class).list();
        assertThat(rowLabels).containsExactly("A", "B", "C");
        assertThat(jdbc.sql("""
                        SELECT max(s.seat_number) FROM seats s JOIN sections c ON c.id = s.section_id
                        WHERE c.event_id = :id AND c.name = 'Stalls'
                        """)
                .param("id", eventId).query(Integer.class).single()).isEqualTo(4);
    }

    @Test
    void aVenueOf2000SeatsIsSaved() throws Exception {
        String id = createEvent(adaToken);
        StringBuilder sections = new StringBuilder();
        for (int i = 1; i <= 4; i++) {
            sections.append(i > 1 ? "," : "")
                    .append("{\"name\":\"Block ").append(i).append("\",\"tier\":\"Standard\",\"rows\":25,\"seatsPerRow\":20}");
        }
        String json = "{\"currency\":\"USD\",\"priceTiers\":[{\"name\":\"Standard\",\"priceMinor\":2500}],"
                + "\"sections\":[" + sections + "]}";

        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", json))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalSeats").value(2000));

        assertThat(seatCount(id)).isEqualTo(2000);
    }

    @Test
    void savingAgainReplacesTheWholeLayout() throws Exception {
        String id = createEvent(adaToken);
        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());
        String smaller = """
                {"currency":"USD",
                 "priceTiers":[{"name":"General","priceMinor":2500}],
                 "sections":[{"name":"Floor","tier":"General","rows":1,"seatsPerRow":6}]}""";

        mockMvc.perform(saveLayout(id, adaToken, "\"1\"", smaller))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.currency").value("USD"))
                .andExpect(jsonPath("$.totalSeats").value(6))
                .andExpect(jsonPath("$.priceTiers", hasSize(1)))
                .andExpect(jsonPath("$.sections", hasSize(1)));

        assertThat(seatCount(id)).isEqualTo(6);
        UUID eventId = UUID.fromString(id);
        assertThat(jdbc.sql("SELECT count(*) FROM sections WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single()).isEqualTo(1);
        assertThat(jdbc.sql("SELECT count(*) FROM price_tiers WHERE event_id = :id")
                .param("id", eventId).query(Long.class).single()).isEqualTo(1);
    }

    @Test
    void layoutsOfDifferentEventsDoNotTouchEachOther() throws Exception {
        String first = createEvent(adaToken);
        String second = createEvent(graceToken);

        mockMvc.perform(saveLayout(first, adaToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());
        mockMvc.perform(saveLayout(second, graceToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());
        mockMvc.perform(saveLayout(first, adaToken, "\"1\"", TWO_SECTIONS)).andExpect(status().isOk());

        assertThat(seatCount(first)).isEqualTo(22);
        assertThat(seatCount(second)).isEqualTo(22);
    }

    @Test
    void savingALayoutBumpsTheEventVersionSoAnOldEventEditIsRefused() throws Exception {
        String id = createEvent(adaToken);
        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());
        String edit = "{\"title\":\"Renamed\",\"startsAt\":\"" + FUTURE + "\",\"venueName\":\"Hall\",\"city\":\"Tashkent\"}";

        mockMvc.perform(withToken(put("/api/v1/events/" + id), adaToken).header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isPreconditionFailed());
        mockMvc.perform(withToken(put("/api/v1/events/" + id), adaToken).header("If-Match", "\"1\"")
                        .contentType(MediaType.APPLICATION_JSON).content(edit))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.version").value(2));
        assertThat(seatCount(id)).isEqualTo(22);
    }

    // ---- refusals

    @Test
    void withoutIfMatchTheAnswerIs428() throws Exception {
        String id = createEvent(adaToken);

        mockMvc.perform(saveLayout(id, adaToken, null, TWO_SECTIONS))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void aStaleVersionIs412AndTheLayoutStaysAsItWas() throws Exception {
        String id = createEvent(adaToken);
        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());

        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        assertThat(seatCount(id)).isEqualTo(22);
    }

    @Test
    void anotherOrganizersEventAndAnUnknownEventLookMissing() throws Exception {
        String id = createEvent(adaToken);

        mockMvc.perform(saveLayout(id, graceToken, "\"0\"", TWO_SECTIONS))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(saveLayout(UUID.randomUUID().toString(), adaToken, "\"0\"", TWO_SECTIONS))
                .andExpect(status().isNotFound());
        assertThat(seatCount(id)).isZero();
    }

    @Test
    void aPublishedEventKeepsItsLayout() throws Exception {
        String id = createEvent(adaToken);
        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());
        publish(id);

        mockMvc.perform(saveLayout(id, adaToken, "\"1\"", TWO_SECTIONS))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

        assertThat(seatCount(id)).isEqualTo(22);
    }

    @Test
    void aCustomerAndAVisitorAreRefused() throws Exception {
        String id = createEvent(adaToken);

        mockMvc.perform(saveLayout(id, tokenFor(UUID.randomUUID(), "CUSTOMER"), "\"0\"", TWO_SECTIONS))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/events/" + id + "/layout").header("If-Match", "\"0\"")
                        .contentType(MediaType.APPLICATION_JSON).content(TWO_SECTIONS))
                .andExpect(status().isUnauthorized());
        assertThat(seatCount(id)).isZero();
    }

    @Test
    void anInvalidLayoutIsRefusedAndChangesNothing() throws Exception {
        String id = createEvent(adaToken);
        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());
        String broken = """
                {"currency":"UZS",
                 "priceTiers":[{"name":"Standard","priceMinor":0}],
                 "sections":[{"name":"Stalls","tier":"Gold","rows":99,"seatsPerRow":4}]}""";

        mockMvc.perform(saveLayout(id, adaToken, "\"1\"", broken))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", org.hamcrest.Matchers.containsInAnyOrder(
                        "priceTiers[0].priceMinor", "sections[0].tier", "sections[0].rows")));

        assertThat(seatCount(id)).isEqualTo(22);
        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id), adaToken))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void aFieldWeDoNotKnowIsRejected() throws Exception {
        String id = createEvent(adaToken);
        String json = """
                {"currency":"UZS","extra":true,
                 "priceTiers":[{"name":"Standard","priceMinor":100}],
                 "sections":[{"name":"Stalls","tier":"Standard","rows":1,"seatsPerRow":1}]}""";

        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    // ---- two saves at once

    @Test
    void twoSavesWithTheSameVersionOnlyOneWins() throws Exception {
        String id = createEvent(adaToken);
        String small = """
                {"currency":"UZS","priceTiers":[{"name":"A","priceMinor":100}],
                 "sections":[{"name":"S","tier":"A","rows":1,"seatsPerRow":3}]}""";
        String large = """
                {"currency":"UZS","priceTiers":[{"name":"A","priceMinor":100}],
                 "sections":[{"name":"S","tier":"A","rows":1,"seatsPerRow":8}]}""";
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<Integer>> saves = new ArrayList<>();
        for (String json : List.of(small, large)) {
            saves.add(() -> {
                start.await();
                return mockMvc.perform(saveLayout(id, adaToken, "\"0\"", json)).andReturn().getResponse().getStatus();
            });
        }
        List<Future<Integer>> futures = new ArrayList<>();
        for (Callable<Integer> save : saves) {
            futures.add(pool.submit(save));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        pool.shutdown();
        Collections.sort(statuses);

        assertThat(statuses).containsExactly(200, 412);
        assertThat(seatCount(id)).isIn(3L, 8L);
        assertThat(jdbc.sql("SELECT count(*) FROM sections WHERE event_id = :id")
                .param("id", UUID.fromString(id)).query(Long.class).single()).isEqualTo(1);
    }

    // ---- reading it back

    @Test
    void theOwnerReadsTheLayoutBackWithTheVersionToSendBack() throws Exception {
        String id = createEvent(adaToken);
        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());

        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id + "/layout"), adaToken))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andExpect(jsonPath("$.totalSeats").value(22))
                .andExpect(jsonPath("$.sections[*].name", contains("Stalls", "Front")))
                .andExpect(jsonPath("$.priceTiers[*].name", contains("Standard", "VIP")));
    }

    @Test
    void anEventWithoutALayoutHasAnEmptyOne() throws Exception {
        String id = createEvent(adaToken);

        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id + "/layout"), adaToken))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.currency").value((Object) null))
                .andExpect(jsonPath("$.priceTiers", empty()))
                .andExpect(jsonPath("$.sections", empty()))
                .andExpect(jsonPath("$.totalSeats").value(0));
    }

    @Test
    void theOwnerReadsTheEventWithItsVersionAndOthersCannot() throws Exception {
        String id = createEvent(adaToken);

        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id), adaToken))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("DRAFT"));
        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id), graceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id + "/layout"), graceToken))
                .andExpect(status().isNotFound());
        mockMvc.perform(withToken(get("/api/v1/events/mine/" + UUID.randomUUID()), adaToken))
                .andExpect(status().isNotFound());
    }

    @Test
    void theMineAreaNeedsAnOrganizer() throws Exception {
        String id = createEvent(adaToken);
        String customer = tokenFor(UUID.randomUUID(), "CUSTOMER");

        mockMvc.perform(get("/api/v1/events/mine/" + id)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/events/mine/" + id + "/layout")).andExpect(status().isUnauthorized());
        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id), customer)).andExpect(status().isForbidden());
        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id + "/layout"), customer))
                .andExpect(status().isForbidden());
    }

    // ---- housekeeping

    @Test
    void deletingAnEventTakesItsLayoutWithIt() throws Exception {
        String id = createEvent(adaToken);
        mockMvc.perform(saveLayout(id, adaToken, "\"0\"", TWO_SECTIONS)).andExpect(status().isOk());

        jdbc.sql("DELETE FROM events WHERE id = :id").param("id", UUID.fromString(id)).update();

        assertThat(seatCount(id)).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM sections").query(Long.class).single()).isZero();
        assertThat(jdbc.sql("SELECT count(*) FROM price_tiers").query(Long.class).single()).isZero();
    }
}
