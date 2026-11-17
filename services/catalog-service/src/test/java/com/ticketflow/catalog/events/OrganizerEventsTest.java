package com.ticketflow.catalog.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * What an organizer can do with events, against contracts/catalog.openapi.yaml, through the real filters and
 * on a real PostgreSQL (ADR-0004: role and ownership, ADR-0009: validation, If-Match and error codes).
 */
class OrganizerEventsTest extends AbstractPostgresIntegrationTest {

    private static final String FUTURE = "2099-01-01T18:00:00Z";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private BearerTokenFilter bearerTokenFilter;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;
    private final UUID ada = UUID.randomUUID();
    private final UUID grace = UUID.randomUUID();
    private String adaToken;
    private String graceToken;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM events").update();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, bearerTokenFilter)
                .build();
        adaToken = tokenFor(ada, "CUSTOMER", "ORGANIZER");
        graceToken = tokenFor(grace, "CUSTOMER", "ORGANIZER");
    }

    private static String body(String title, String startsAt, String venue, String city) {
        return "{\"title\":\"" + title + "\",\"startsAt\":\"" + startsAt + "\",\"venueName\":\""
                + venue + "\",\"city\":\"" + city + "\"}";
    }

    private MockHttpServletRequestBuilder withToken(MockHttpServletRequestBuilder request, String token) {
        return request.header("Authorization", "Bearer " + token);
    }

    private String createEvent(String token, String title, String startsAt) throws Exception {
        String response = mockMvc.perform(withToken(post("/api/v1/events"), token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body(title, startsAt, "Main Hall", "Tashkent")))
                .andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(response, "$.id");
    }

    private void publish(String id) {
        jdbc.sql("UPDATE events SET published_at = now() WHERE id = :id").param("id", UUID.fromString(id)).update();
    }

    private MockHttpServletRequestBuilder update(String id, String token, String ifMatch, String json) {
        MockHttpServletRequestBuilder request = withToken(put("/api/v1/events/" + id), token)
                .contentType(MediaType.APPLICATION_JSON).content(json);
        return ifMatch == null ? request : request.header("If-Match", ifMatch);
    }

    // ---- creating

    @Test
    void anOrganizerCreatesADraft() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/events"), adaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("  Jazz Night  ", FUTURE, "Navoi Theatre", "Tashkent")))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", startsWith("/api/v1/events/")))
                .andExpect(header().string("ETag", "\"0\""))
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.title").value("Jazz Night"))
                .andExpect(jsonPath("$.startsAt").value(FUTURE))
                .andExpect(jsonPath("$.venueName").value("Navoi Theatre"))
                .andExpect(jsonPath("$.city").value("Tashkent"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(0));
    }

    @Test
    void theOwnerIsTheCallerAndNeverComesFromTheBody() throws Exception {
        String id = createEvent(adaToken, "Mine", FUTURE);

        String owner = jdbc.sql("SELECT owner_id::text FROM events WHERE id = :id")
                .param("id", UUID.fromString(id)).query(String.class).single();
        assertThat(owner).isEqualTo(ada.toString());
    }

    @Test
    void aBodyWithAFieldWeDoNotKnowIsRejected() throws Exception {
        String json = "{\"title\":\"X\",\"startsAt\":\"" + FUTURE
                + "\",\"venueName\":\"V\",\"city\":\"C\",\"ownerId\":\"" + grace + "\"}";

        mockMvc.perform(withToken(post("/api/v1/events"), adaToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void everyProblemInABodyIsReportedAtOnce() throws Exception {
        String json = "{\"title\":\"   \",\"startsAt\":\"2001-01-01T00:00:00Z\",\"city\":\""
                + "x".repeat(101) + "\"}";

        mockMvc.perform(withToken(post("/api/v1/events"), adaToken)
                        .contentType(MediaType.APPLICATION_JSON).content(json))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[*].field", containsInAnyOrder("title", "startsAt", "venueName", "city")));
    }

    @Test
    void controlCharactersInATextAreRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/events"), adaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("Two\\nlines", FUTURE, "V", "C")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("title"));
    }

    @Test
    void aDateThatIsNotADateIsRejected() throws Exception {
        mockMvc.perform(withToken(post("/api/v1/events"), adaToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(body("X", "next friday", "V", "C")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aDraftIsNotInThePublicList() throws Exception {
        createEvent(adaToken, "Secret draft", FUTURE);

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(0)));
    }

    // ---- who may

    @Test
    void withoutATokenTheAnswerIs401() throws Exception {
        mockMvc.perform(post("/api/v1/events").contentType(MediaType.APPLICATION_JSON)
                        .content(body("X", FUTURE, "V", "C")))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mockMvc.perform(get("/api/v1/events/mine"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aCustomerIsForbiddenAndNothingIsCreated() throws Exception {
        String customer = tokenFor(UUID.randomUUID(), "CUSTOMER");

        mockMvc.perform(withToken(post("/api/v1/events"), customer)
                        .contentType(MediaType.APPLICATION_JSON).content(body("X", FUTURE, "V", "C")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(withToken(get("/api/v1/events/mine"), customer))
                .andExpect(status().isForbidden());
        assertThat(
                jdbc.sql("SELECT count(*) FROM events").query(Long.class).single()).isZero();
    }

    // ---- updating

    @Test
    void theOwnerUpdatesADraftWithTheVersionTheySaw() throws Exception {
        String id = createEvent(adaToken, "Old title", FUTURE);

        mockMvc.perform(update(id, adaToken, "\"0\"", body("New title", "2099-02-02T10:00:00Z", "Hall 2", "Samarkand")))
                .andExpect(status().isOk())
                .andExpect(header().string("ETag", "\"1\""))
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.title").value("New title"))
                .andExpect(jsonPath("$.startsAt").value("2099-02-02T10:00:00Z"))
                .andExpect(jsonPath("$.venueName").value("Hall 2"))
                .andExpect(jsonPath("$.city").value("Samarkand"))
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.version").value(1));
    }

    @Test
    void anUpdateWithoutIfMatchIs428() throws Exception {
        String id = createEvent(adaToken, "Title", FUTURE);

        mockMvc.perform(update(id, adaToken, null, body("Changed", FUTURE, "V", "C")))
                .andExpect(status().isPreconditionRequired())
                .andExpect(jsonPath("$.code").value("PRECONDITION_REQUIRED"));
    }

    @Test
    void aMalformedIfMatchIs400() throws Exception {
        String id = createEvent(adaToken, "Title", FUTURE);

        mockMvc.perform(update(id, adaToken, "0", body("Changed", FUTURE, "V", "C")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("If-Match"));
    }

    @Test
    void aStaleVersionIs412AndNothingChanges() throws Exception {
        String id = createEvent(adaToken, "Title", FUTURE);
        mockMvc.perform(update(id, adaToken, "\"0\"", body("First change", FUTURE, "V", "C")))
                .andExpect(status().isOk());

        mockMvc.perform(update(id, adaToken, "\"0\"", body("Second change", FUTURE, "V", "C")))
                .andExpect(status().isPreconditionFailed())
                .andExpect(jsonPath("$.code").value("PRECONDITION_FAILED"));

        mockMvc.perform(withToken(get("/api/v1/events/mine"), adaToken))
                .andExpect(jsonPath("$.items[0].title").value("First change"))
                .andExpect(jsonPath("$.items[0].version").value(1));
    }

    @Test
    void anotherOrganizersEventLooksMissingAndStaysUnchanged() throws Exception {
        String id = createEvent(adaToken, "Ada's event", FUTURE);

        mockMvc.perform(update(id, graceToken, "\"0\"", body("Hijacked", FUTURE, "V", "C")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));

        mockMvc.perform(withToken(get("/api/v1/events/mine"), adaToken))
                .andExpect(jsonPath("$.items[0].title").value("Ada's event"))
                .andExpect(jsonPath("$.items[0].version").value(0));
    }

    @Test
    void anUnknownEventIs404() throws Exception {
        mockMvc.perform(update(UUID.randomUUID().toString(), adaToken, "\"0\"", body("X", FUTURE, "V", "C")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anIdThatIsNotAUuidIs400() throws Exception {
        mockMvc.perform(update("mine", adaToken, "\"0\"", body("X", FUTURE, "V", "C")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void aPublishedEventCannotBeEdited() throws Exception {
        String id = createEvent(adaToken, "Live event", FUTURE);
        publish(id);

        mockMvc.perform(update(id, adaToken, "\"0\"", body("Changed", FUTURE, "V", "C")))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_EDITABLE"));
    }

    @Test
    void anInvalidUpdateChangesNothing() throws Exception {
        String id = createEvent(adaToken, "Title", FUTURE);

        mockMvc.perform(update(id, adaToken, "\"0\"", body("Changed", "2001-01-01T00:00:00Z", "V", "C")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("startsAt"));

        mockMvc.perform(withToken(get("/api/v1/events/mine"), adaToken))
                .andExpect(jsonPath("$.items[0].title").value("Title"))
                .andExpect(jsonPath("$.items[0].version").value(0));
    }

    // ---- my events

    @Test
    void myEventsListsOnlyMyEventsDraftsIncludedInStartOrder() throws Exception {
        createEvent(adaToken, "Ada later", "2099-03-01T10:00:00Z");
        String earlier = createEvent(adaToken, "Ada earlier", "2099-02-01T10:00:00Z");
        createEvent(graceToken, "Grace's", "2099-01-01T10:00:00Z");
        publish(earlier);

        mockMvc.perform(withToken(get("/api/v1/events/mine"), adaToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].title").value("Ada earlier"))
                .andExpect(jsonPath("$.items[0].status").value("PUBLISHED"))
                .andExpect(jsonPath("$.items[1].title").value("Ada later"))
                .andExpect(jsonPath("$.items[1].status").value("DRAFT"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void myEventsIsPaged() throws Exception {
        createEvent(adaToken, "One", "2099-01-01T10:00:00Z");
        createEvent(adaToken, "Two", "2099-01-02T10:00:00Z");
        createEvent(adaToken, "Three", "2099-01-03T10:00:00Z");

        mockMvc.perform(withToken(get("/api/v1/events/mine"), adaToken).param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title").value("Three"))
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void myEventsRejectsBadPaging() throws Exception {
        mockMvc.perform(withToken(get("/api/v1/events/mine"), adaToken).param("size", "51"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("size"));
    }

    // ---- the public list

    @Test
    void thePublicListShowsVenueAndCity() throws Exception {
        String id = createEvent(adaToken, "Public show", FUTURE);
        publish(id);

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].venueName").value("Main Hall"))
                .andExpect(jsonPath("$.items[0].city").value("Tashkent"))
                .andExpect(jsonPath("$.items[0].id").value(id));
    }

    @Test
    void theLocationHeaderPointsAtTheNewEvent() throws Exception {
        MvcResult result = mockMvc.perform(withToken(post("/api/v1/events"), adaToken)
                        .contentType(MediaType.APPLICATION_JSON).content(body("X", FUTURE, "V", "C")))
                .andExpect(status().isCreated())
                .andReturn();
        String id = JsonPath.read(result.getResponse().getContentAsString(), "$.id");

        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/api/v1/events/" + id);
    }
}
