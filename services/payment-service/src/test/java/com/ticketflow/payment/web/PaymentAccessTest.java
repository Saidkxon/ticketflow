package com.ticketflow.payment.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.payment.AbstractPostgresIntegrationTest;
import com.ticketflow.payment.security.ServiceTokenFilter;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/**
 * The whole application with its real filters: the service token check and the error format work together
 * (ADR-0004, Part D; test obligation 7). Payment has no endpoint of its own yet, so a test-only endpoint under the
 * internal prefix stands in for the ones that PR 18 adds.
 */
@Import(PaymentAccessTest.ProbeController.class)
class PaymentAccessTest extends AbstractPostgresIntegrationTest {

    private static final String PROBE = "/internal/v1/probe";
    private static final String SCOPE = "probe.read";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    private ServiceTokenFilter serviceTokenFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, serviceTokenFilter)
                .build();
    }

    @Test
    void aRequestWithoutATokenIsRefusedBeforeAnythingElseHappens() throws Exception {
        mockMvc.perform(get(PROBE))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    @Test
    void aUnknownInternalPathIsAlsoRefusedWithoutAToken() throws Exception {
        // The check does not depend on the path existing: nobody learns which internal paths there are.
        mockMvc.perform(get("/internal/v1/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aUsersAccessTokenIsRefusedOnAnInternalEndpoint() throws Exception {
        String token = userToken(UUID.randomUUID(), "CUSTOMER", "ADMIN");

        mockMvc.perform(get(PROBE).header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void aServiceTokenWithTheRightScopeReachesTheEndpoint() throws Exception {
        String token = serviceToken("booking-service", SCOPE);

        mockMvc.perform(get(PROBE).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().string("hello booking-service"));
    }

    @Test
    void aServiceTokenWithAnotherScopeIsForbidden() throws Exception {
        String token = serviceToken("booking-service", "payment.refund.create");

        mockMvc.perform(get(PROBE).header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("FORBIDDEN"))
                .andExpect(jsonPath("$.correlationId").exists());
    }

    /** Test-only endpoint. Registered with @Import, because Spring Boot does not scan classes nested in tests. */
    @RestController
    static class ProbeController {

        @GetMapping(PROBE)
        String probe(HttpServletRequest request) {
            return "hello " + ServiceTokenFilter.requireScope(request, SCOPE).caller();
        }
    }
}
