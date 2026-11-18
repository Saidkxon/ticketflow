package com.ticketflow.catalog.events;

import static org.assertj.core.api.Assertions.assertThat;
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
 * Publishing an event and reading its frozen layout (FR-3, ADR-0012, ADR-0003) through the real filters and on a
 * real PostgreSQL: the publish changes the event and writes the outbox message in one transaction, and a
 * published layout can no longer change.
 */
class PublishTest extends AbstractPostgresIntegrationTest {

    private static final String FUTURE = "2099-01-01T18:00:00Z";

    private static final String LAYOUT = """
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
        jdbc.sql("DELETE FROM outbox_events").update();
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

    /** A draft with a saved layout, at version 1. */
    private String draftWithLayout(String token) throws Exception {
        String id = createEvent(token);
        mockMvc.perform(withToken(put("/api/v1/events/" + id + "/layout"), token)
                        .header("If-Match", "\"0\"").contentType(MediaType.APPLICATION_JSON).content(LAYOUT))
                .andExpect(status().isOk());
        return id;
    }

    private MockHttpServletRequestBuilder publish(String id, String token, String ifMatch) {
        MockHttpServletRequestBuilder request = withToken(post("/api/v1/events/" + id + "/publish"), token);
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    private long outboxRows() {
        return jdbc.sql("SELECT count(*) FROM outbox_events").query(Long.class).single();
    }

    private boolean isPublished(String id) {
        return jdbc.sql("SELECT published_at IS NOT NULL FROM events WHERE id = :id")
                .param("id", UUID.fromString(id)).query(Boolean.class).single();
    }

    // ---- publishing

    @Test
    void publishingAFinishedDraftPublishesItAndShowsItInThePublicList() throws Exception {
        String id = draftWithLayout(adaToken);

        mockMvc.perform(publish(id, adaToken, "\"1\""))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.status").value("PUBLISHED"))
                .andExpect(jsonPath("$.version").value(2));

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].id").value(id));
    }

    @Test
    void publishingWritesExactlyOneOutboxMessageWithTheEnvelope() throws Exception {
        String id = draftWithLayout(adaToken);
        String correlation = UUID.randomUUID().toString();

        mockMvc.perform(publish(id, adaToken, "\"1\"").header(CorrelationIdFilter.HEADER, correlation))
                .andExpect(status().isOk());

        assertThat(outboxRows()).isEqualTo(1);
        var row = jdbc.sql("""
                        SELECT id, aggregate_type, aggregate_id, event_type, payload::text AS payload, published_at
                        FROM outbox_events
                        """)
                .query((rs, n) -> new String[] {rs.getString("id"), rs.getString("aggregate_type"),
                        rs.getString("aggregate_id"), rs.getString("event_type"), rs.getString("payload"),
                        rs.getString("published_at")})
                .single();
        assertThat(row[1]).isEqualTo("event");
        assertThat(row[2]).isEqualTo(id);
        assertThat(row[3]).isEqualTo("LayoutPublished");
        assertThat(row[5]).isNull();
        String payload = row[4];
        assertThat((String) JsonPath.read(payload, "$.eventId")).isEqualTo(row[0]);
        assertThat((String) JsonPath.read(payload, "$.eventType")).isEqualTo("LayoutPublished");
        assertThat((Integer) JsonPath.read(payload, "$.eventVersion")).isEqualTo(1);
        assertThat((String) JsonPath.read(payload, "$.producer")).isEqualTo("catalog-service");
        assertThat((String) JsonPath.read(payload, "$.correlationId")).isEqualTo(correlation);
        assertThat((String) JsonPath.read(payload, "$.aggregateId")).isEqualTo(id);
        assertThat((String) JsonPath.read(payload, "$.data.eventId")).isEqualTo(id);
        assertThat((Integer) JsonPath.read(payload, "$.data.layoutVersion")).isEqualTo(2);
        assertThat((String) JsonPath.read(payload, "$.occurredAt")).endsWith("Z");
    }

    @Test
    void aDraftWithoutALayoutIsNotReadyAndNothingChanges() throws Exception {
        String id = createEvent(adaToken);

        mockMvc.perform(publish(id, adaToken, "\"0\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_READY"));

        assertThat(isPublished(id)).isFalse();
        assertThat(outboxRows()).isZero();
        mockMvc.perform(withToken(get("/api/v1/events/mine/" + id), adaToken))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void anEventThatAlreadyStartedIsNotReadyAndNothingChanges() throws Exception {
        String id = draftWithLayout(adaToken);
        jdbc.sql("UPDATE events SET starts_at = now() - interval '1 hour' WHERE id = :id")
                .param("id", UUID.fromString(id)).update();

        mockMvc.perform(publish(id, adaToken, "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_READY"));

        assertThat(isPublished(id)).isFalse();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void publishingTwiceIsRefusedAndWritesNoSecondMessage() throws Exception {
        String id = draftWithLayout(adaToken);
        mockMvc.perform(publish(id, adaToken, "\"1\"")).andExpect(status().isOk());

        mockMvc.perform(publish(id, adaToken, "\"2\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_PUBLISHED"));
        mockMvc.perform(publish(id, adaToken, "\"1\""))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_ALREADY_PUBLISHED"));

        assertThat(outboxRows()).isEqualTo(1);
    }

    @Test
    void aStaleVersionIsRefusedWith412() throws Exception {
        String id = draftWithLayout(adaToken);

        mockMvc.perform(publish(id, adaToken, "\"0\""))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        assertThat(isPublished(id)).isFalse();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void aMissingOrMalformedIfMatchIsRefused() throws Exception {
        String id = draftWithLayout(adaToken);

        mockMvc.perform(publish(id, adaToken, null))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
        mockMvc.perform(publish(id, adaToken, "1"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        assertThat(isPublished(id)).isFalse();
    }

    @Test
    void anotherOrganizersEventAndAMissingEventLookTheSame() throws Exception {
        String id = draftWithLayout(adaToken);

        mockMvc.perform(publish(id, graceToken, "\"1\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(publish(UUID.randomUUID().toString(), adaToken, "\"1\""))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        assertThat(isPublished(id)).isFalse();
        assertThat(outboxRows()).isZero();
    }

    @Test
    void publishNeedsATokenWithTheOrganizerRole() throws Exception {
        String id = draftWithLayout(adaToken);
        String customer = tokenFor(UUID.randomUUID(), "CUSTOMER");

        mockMvc.perform(post("/api/v1/events/" + id + "/publish").header("If-Match", "\"1\""))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(publish(id, customer, "\"1\""))
                .andExpect(status().isForbidden());

        assertThat(isPublished(id)).isFalse();
    }

    @Test
    void twoPublishCallsAtTheSameTimeGiveOneSuccessAndOneMessage() throws Exception {
        String id = draftWithLayout(adaToken);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Integer>> futures = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            Callable<Integer> call = () -> {
                start.await();
                return mockMvc.perform(publish(id, adaToken, "\"1\"")).andReturn().getResponse().getStatus();
            };
            futures.add(pool.submit(call));
        }
        start.countDown();
        List<Integer> statuses = new ArrayList<>();
        for (Future<Integer> future : futures) {
            statuses.add(future.get());
        }
        pool.shutdown();
        Collections.sort(statuses);

        assertThat(statuses).containsExactly(200, 409);
        assertThat(outboxRows()).isEqualTo(1);
    }

    // ---- after publishing

    @Test
    void aPublishedEventAndItsLayoutCannotBeChangedAnymore() throws Exception {
        String id = draftWithLayout(adaToken);
        mockMvc.perform(publish(id, adaToken, "\"1\"")).andExpect(status().isOk());
        long seatsBefore = jdbc.sql("SELECT count(*) FROM seats").query(Long.class).single();

        mockMvc.perform(withToken(put("/api/v1/events/" + id + "/layout"), adaToken)
                        .header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON).content(LAYOUT))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
        mockMvc.perform(withToken(put("/api/v1/events/" + id), adaToken)
                        .header("If-Match", "\"2\"").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"New\",\"startsAt\":\"" + FUTURE
                                + "\",\"venueName\":\"Hall\",\"city\":\"Tashkent\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));

        assertThat(jdbc.sql("SELECT count(*) FROM seats").query(Long.class).single()).isEqualTo(seatsBefore);
    }

    // ---- the public layout

    @Test
    void thePublicLayoutHasEverySeatWithItsStoredIdAndNeedsNoToken() throws Exception {
        String id = draftWithLayout(adaToken);
        mockMvc.perform(publish(id, adaToken, "\"1\"")).andExpect(status().isOk());

        String body = mockMvc.perform(get("/api/v1/events/" + id + "/layout"))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"2\""))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("public")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("max-age=")))
                .andExpect(jsonPath("$.eventId").value(id))
                .andExpect(jsonPath("$.layoutVersion").value(2))
                .andExpect(jsonPath("$.currency").value("UZS"))
                .andExpect(jsonPath("$.totalSeats").value(22))
                .andExpect(jsonPath("$.priceTiers", hasSize(2)))
                .andExpect(jsonPath("$.priceTiers[0].name").value("Standard"))
                .andExpect(jsonPath("$.priceTiers[0].priceMinor").value(150000))
                .andExpect(jsonPath("$.sections", hasSize(2)))
                .andExpect(jsonPath("$.sections[0].name").value("Stalls"))
                .andExpect(jsonPath("$.sections[0].tier").value("Standard"))
                .andExpect(jsonPath("$.sections[0].rows", hasSize(3)))
                .andExpect(jsonPath("$.sections[0].rows[0].label").value("A"))
                .andExpect(jsonPath("$.sections[0].rows[0].seats", hasSize(4)))
                .andExpect(jsonPath("$.sections[0].rows[0].seats[0].number").value(1))
                .andExpect(jsonPath("$.sections[1].name").value("Front"))
                .andExpect(jsonPath("$.sections[1].rows", hasSize(2)))
                .andExpect(jsonPath("$.sections[1].rows[1].seats", hasSize(5)))
                .andReturn().getResponse().getContentAsString();

        List<String> seatIds = JsonPath.read(body, "$.sections[*].rows[*].seats[*].id");
        List<String> stored = jdbc.sql("SELECT id::text FROM seats WHERE event_id = :id")
                .param("id", UUID.fromString(id)).query(String.class).list();
        assertThat(seatIds).hasSize(22).doesNotHaveDuplicates().containsExactlyInAnyOrderElementsOf(stored);
        String tierId = JsonPath.read(body, "$.sections[0].tierId");
        List<String> tierIds = JsonPath.read(body, "$.priceTiers[*].id");
        assertThat(tierIds).contains(tierId);
    }

    @Test
    void thePublicLayoutAnswers304WhenTheTagStillMatches() throws Exception {
        String id = draftWithLayout(adaToken);
        mockMvc.perform(publish(id, adaToken, "\"1\"")).andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/events/" + id + "/layout").header("If-None-Match", "\"2\""))
                .andExpect(status().isNotModified())
                .andExpect(header().string("ETag", "\"2\""));
        mockMvc.perform(get("/api/v1/events/" + id + "/layout").header("If-None-Match", "\"1\""))
                .andExpect(status().isOk());
    }

    @Test
    void theLayoutOfADraftOrAMissingEventIsNotFound() throws Exception {
        String id = draftWithLayout(adaToken);

        mockMvc.perform(get("/api/v1/events/" + id + "/layout"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/events/" + UUID.randomUUID() + "/layout"))
                .andExpect(status().isNotFound());
    }

    @Test
    void theSeatIdsStayTheSameWhenTheLayoutIsReadAgain() throws Exception {
        String id = draftWithLayout(adaToken);
        mockMvc.perform(publish(id, adaToken, "\"1\"")).andExpect(status().isOk());

        String first = mockMvc.perform(get("/api/v1/events/" + id + "/layout"))
                .andReturn().getResponse().getContentAsString();
        String second = mockMvc.perform(get("/api/v1/events/" + id + "/layout"))
                .andReturn().getResponse().getContentAsString();

        assertThat(second).isEqualTo(first);
    }
}
