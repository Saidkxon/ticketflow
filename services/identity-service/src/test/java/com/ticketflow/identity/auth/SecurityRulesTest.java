package com.ticketflow.identity.auth;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ticketflow.identity.AbstractPostgresIntegrationTest;
import com.ticketflow.identity.web.CorrelationIdFilter;
import jakarta.servlet.Filter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;

/** ADR-0004: everything is closed unless listed, and refusals are plain problem+json (ADR-0009). */
@SpringBootTest
class SecurityRulesTest extends AbstractPostgresIntegrationTest {

    private static final String CORRELATION_ID = "11111111-2222-3333-4444-555555555555";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    @Autowired
    @Qualifier("springSecurityFilterChain")
    private Filter securityFilterChain;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter, securityFilterChain)
                .build();
    }

    @Test
    void aProtectedPathWithoutATokenGetsAPlain401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(header().doesNotExist("WWW-Authenticate"));
    }

    @Test
    void aGarbageBearerTokenGetsTheSamePlain401() throws Exception {
        mockMvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not.a.token"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"))
                .andExpect(content().string(not(containsString("not.a.token"))));
    }

    @Test
    void unknownPathsAreClosedToo() throws Exception {
        mockMvc.perform(post("/api/v1/auth/not-a-real-endpoint").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
    }

    @Test
    void noSessionCookieIsEverIssued() throws Exception {
        mockMvc.perform(get("/api/v1/users/me"))
                .andExpect(header().doesNotExist("Set-Cookie"));
    }
}
