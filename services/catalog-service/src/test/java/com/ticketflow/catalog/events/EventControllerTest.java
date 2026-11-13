package com.ticketflow.catalog.events;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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

    private void insert(String title, String startsAt, boolean published) {
        String publishedAt = published ? "now()" : "NULL";
        jdbc.sql("INSERT INTO events (id, title, starts_at, published_at) "
                        + "VALUES (:id, :title, :startsAt, " + publishedAt + ")")
                .param("id", UUID.randomUUID())
                .param("title", title)
                .param("startsAt", OffsetDateTime.parse(startsAt))
                .update();
    }
}
