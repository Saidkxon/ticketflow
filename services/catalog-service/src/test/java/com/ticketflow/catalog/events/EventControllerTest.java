package com.ticketflow.catalog.events;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.catalog.AbstractPostgresIntegrationTest;
import com.ticketflow.catalog.web.CorrelationIdFilter;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** The event list against contracts/catalog.openapi.yaml, on a real PostgreSQL (ADR-0005). */
@SpringBootTest
class EventControllerTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private JdbcClient jdbc;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM events").update();
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter)
                .build();
    }

    @Test
    void emptyCatalogReturnsAnEmptyPageWithDefaults() throws Exception {
        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(jsonPath("$.items", hasSize(0)))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.totalItems").value(0));
    }

    @Test
    void listsOnlyPublishedEventsInStartOrder() throws Exception {
        insert("Later show", "2026-12-02T18:00:00Z", true);
        insert("Earlier show", "2026-12-01T18:00:00Z", true);
        insert("Unpublished draft", "2026-11-30T18:00:00Z", false);

        mockMvc.perform(get("/api/v1/events"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(2)))
                .andExpect(jsonPath("$.items[0].title").value("Earlier show"))
                .andExpect(jsonPath("$.items[0].startsAt").value("2026-12-01T18:00:00Z"))
                .andExpect(jsonPath("$.items[0].id").isString())
                .andExpect(jsonPath("$.items[0].venueName").value("Main Hall"))
                .andExpect(jsonPath("$.items[0].city").value("Tashkent"))
                .andExpect(jsonPath("$.items[1].title").value("Later show"))
                .andExpect(jsonPath("$.totalItems").value(2));
    }

    @Test
    void paginatesWithPageAndSize() throws Exception {
        insert("One", "2026-12-01T18:00:00Z", true);
        insert("Two", "2026-12-02T18:00:00Z", true);
        insert("Three", "2026-12-03T18:00:00Z", true);

        mockMvc.perform(get("/api/v1/events").param("page", "1").param("size", "2"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(1)))
                .andExpect(jsonPath("$.items[0].title").value("Three"))
                .andExpect(jsonPath("$.page").value(1))
                .andExpect(jsonPath("$.size").value(2))
                .andExpect(jsonPath("$.totalItems").value(3));
    }

    @Test
    void sizeAboveTheMaximumIsRejected() throws Exception {
        expectInvalid("size", "51", "size");
    }

    @Test
    void sizeZeroIsRejected() throws Exception {
        expectInvalid("size", "0", "size");
    }

    @Test
    void negativePageIsRejected() throws Exception {
        expectInvalid("page", "-1", "page");
    }

    private void expectInvalid(String parameter, String value, String expectedField) throws Exception {
        mockMvc.perform(get("/api/v1/events").param(parameter, value))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.errors[0].field").value(expectedField))
                .andExpect(jsonPath("$.errors[0].problem").isString());
    }

    // ---- one published event (the event page)

    @Test
    void aPublishedEventIsReadByItsIdWithoutAToken() throws Exception {
        UUID id = UUID.randomUUID();
        jdbc.sql("INSERT INTO events (id, title, starts_at, published_at, owner_id, venue_name, city, "
                        + "venue_latitude, venue_longitude) VALUES (:id, 'Concert', :startsAt, now(), :owner, "
                        + "'Main Hall', 'Tashkent', 41.3, 69.2)")
                .param("id", id)
                .param("startsAt", OffsetDateTime.parse("2026-12-01T18:00:00Z"))
                .param("owner", UUID.randomUUID())
                .update();

        mockMvc.perform(get("/api/v1/events/" + id))
                .andExpect(status().isOk())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_JSON))
                .andExpect(header().string("Cache-Control", containsString("max-age=60")))
                .andExpect(jsonPath("$.id").value(id.toString()))
                .andExpect(jsonPath("$.title").value("Concert"))
                .andExpect(jsonPath("$.startsAt").value("2026-12-01T18:00:00Z"))
                .andExpect(jsonPath("$.venueName").value("Main Hall"))
                .andExpect(jsonPath("$.city").value("Tashkent"))
                .andExpect(jsonPath("$.venueLatitude").value(41.3))
                .andExpect(jsonPath("$.venueLongitude").value(69.2))
                .andExpect(jsonPath("$.distanceKm").doesNotExist());
    }

    @Test
    void aDraftAndAnUnknownEventLookTheSame() throws Exception {
        UUID draft = UUID.randomUUID();
        jdbc.sql("INSERT INTO events (id, title, starts_at, owner_id, venue_name, city) "
                        + "VALUES (:id, 'Draft', :startsAt, :owner, 'Main Hall', 'Tashkent')")
                .param("id", draft)
                .param("startsAt", OffsetDateTime.parse("2026-12-01T18:00:00Z"))
                .param("owner", UUID.randomUUID())
                .update();

        mockMvc.perform(get("/api/v1/events/" + draft))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
        mockMvc.perform(get("/api/v1/events/" + UUID.randomUUID()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FOUND"));
    }

    @Test
    void anIdThatIsNotAUuidIsRefused() throws Exception {
        mockMvc.perform(get("/api/v1/events/not-a-uuid"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    private void insert(String title, String startsAt, boolean published) {
        String publishedAt = published ? "now()" : "NULL";
        jdbc.sql("INSERT INTO events (id, title, starts_at, published_at, owner_id, venue_name, city) "
                        + "VALUES (:id, :title, :startsAt, " + publishedAt + ", :owner, 'Main Hall', 'Tashkent')")
                .param("id", UUID.randomUUID())
                .param("title", title)
                .param("startsAt", OffsetDateTime.parse(startsAt))
                .param("owner", UUID.randomUUID())
                .update();
    }
}
