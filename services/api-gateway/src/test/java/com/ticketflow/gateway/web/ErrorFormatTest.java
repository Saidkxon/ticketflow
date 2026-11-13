package com.ticketflow.gateway.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.context.WebApplicationContext;

/** ADR-0009, test obligation 4: every error is valid problem+json with code and correlationId. */
@SpringBootTest
@Import(ErrorFormatTest.ProbeController.class)
class ErrorFormatTest {

    private static final String CORRELATION_ID = "11111111-2222-3333-4444-555555555555";
    private static final String PROBLEM_BASE = "https://api.ticketflow.example/problems/";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private CorrelationIdFilter correlationIdFilter;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .addFilters(correlationIdFilter)
                .build();
    }

    @Test
    void unknownPathReturnsNotFoundProblem() throws Exception {
        mockMvc.perform(get("/does-not-exist").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(404))
                .andExpect(jsonPath("$.code").value("ROUTE_NOT_FOUND"))
                .andExpect(jsonPath("$.type").value(PROBLEM_BASE + "route-not-found"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void badParameterReturnsInvalidRequestProblem() throws Exception {
        mockMvc.perform(get("/probe/number").param("n", "abc")
                        .header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void wrongMethodReturnsMethodNotAllowedProblem() throws Exception {
        mockMvc.perform(post("/probe/number").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isMethodNotAllowed())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID));
    }

    @Test
    void unexpectedExceptionReturnsGenericProblemWithoutLeakingDetails() throws Exception {
        mockMvc.perform(get("/probe/boom").header(CorrelationIdFilter.HEADER, CORRELATION_ID))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.correlationId").value(CORRELATION_ID))
                .andExpect(content().string(not(containsString("secret internal detail"))))
                .andExpect(content().string(not(containsString("IllegalStateException"))))
                .andExpect(content().string(not(containsString("com.ticketflow"))));
    }

    /** Test-only endpoints. Registered with @Import, because Spring Boot does not scan classes nested in tests. */
    @RestController
    static class ProbeController {

        @GetMapping("/probe/boom")
        String boom() {
            throw new IllegalStateException("secret internal detail");
        }

        @GetMapping("/probe/number")
        String number(@RequestParam int n) {
            return "ok " + n;
        }
    }
}
