package com.ticketflow.identity.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

    private static final String UUID_REGEX =
            "^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$";

    private final CorrelationIdFilter filter = new CorrelationIdFilter();

    @Test
    void createsAnIdWhenTheHeaderIsMissing() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches(UUID_REGEX);
    }

    @Test
    void keepsAValidIdFromTheCaller() throws Exception {
        String supplied = UUID.randomUUID().toString();
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(CorrelationIdFilter.HEADER, supplied);
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).isEqualTo(supplied);
    }

    @Test
    void replacesAnInvalidIdSoNothingCanBeInjectedIntoLogs() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        request.addHeader(CorrelationIdFilter.HEADER, "not-a-uuid\r\nInjected: line");
        MockHttpServletResponse response = new MockHttpServletResponse();

        filter.doFilter(request, response, (req, res) -> { });

        assertThat(response.getHeader(CorrelationIdFilter.HEADER)).matches(UUID_REGEX);
    }

    @Test
    void makesTheIdAvailableToLogsDuringTheRequestAndRemovesItAfterwards() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/anything");
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicReference<String> seenInsideTheChain = new AtomicReference<>();

        filter.doFilter(request, response,
                (req, res) -> seenInsideTheChain.set(MDC.get(CorrelationIdFilter.MDC_KEY)));

        assertThat(seenInsideTheChain.get())
                .isEqualTo(response.getHeader(CorrelationIdFilter.HEADER));
        assertThat(MDC.get(CorrelationIdFilter.MDC_KEY)).isNull();
    }
}
