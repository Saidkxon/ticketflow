package com.ticketflow.booking.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.booking.AbstractPostgresIntegrationTest;
import com.ticketflow.booking.security.BearerTokenFilter;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/**
 * The whole application with its real filters: the token check and the error format work together. This is what
 * the stack smoke test relies on while Booking has no endpoints of its own yet.
 */
class BookingAccessTest extends AbstractPostgresIntegrationTest {

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private BearerTokenFilter bearerTokenFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, bearerTokenFilter)
                .build();
    }

    @Test
    void aRequestWithoutATokenIsRefusedBeforeAnythingElseHappens() throws Exception {
        mockMvc.perform(get("/api/v1/bookings/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aSignedInUserGetsTheStandardNotFoundForAPathThatDoesNotExistYet() throws Exception {
        String token = tokenFor(UUID.randomUUID(), "CUSTOMER");

        mockMvc.perform(get("/api/v1/bookings/" + UUID.randomUUID()).header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("NOT_FOUND"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void availabilityNeedsNoTokenSoItReachesTheEndpoint() throws Exception {
        // An event that has no seats in Booking is "not on sale", answered without a token (not with a 401).
        mockMvc.perform(get("/api/v1/events/" + UUID.randomUUID() + "/availability"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("EVENT_NOT_ON_SALE"));
    }
}
