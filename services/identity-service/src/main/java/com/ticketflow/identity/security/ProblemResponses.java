package com.ticketflow.identity.security;

import com.ticketflow.identity.web.CorrelationIdFilter;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import org.slf4j.MDC;

/**
 * Writes an application/problem+json body for errors raised by the security filters, which run
 * before Spring MVC and so cannot use the normal exception handler (ADR-0009). The 401 and 403
 * answers are deliberately plain: no reason is given (ADR-0004).
 */
final class ProblemResponses {

    private static final String PROBLEM_BASE_URI = "https://api.ticketflow.example/problems/";

    private ProblemResponses() {
    }

    static void write(HttpServletResponse response, int status, String code, String title, String detail)
            throws IOException {
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        StringBuilder body = new StringBuilder(256)
                .append("{\"type\":\"").append(PROBLEM_BASE_URI)
                .append(code.toLowerCase(java.util.Locale.ROOT).replace('_', '-')).append('"')
                .append(",\"title\":\"").append(title).append('"')
                .append(",\"status\":").append(status)
                .append(",\"code\":\"").append(code).append('"')
                .append(",\"detail\":\"").append(detail).append('"');
        if (correlationId != null) {
            // Always a valid UUID: CorrelationIdFilter replaces anything else.
            body.append(",\"correlationId\":\"").append(correlationId).append('"');
        }
        body.append('}');

        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(body.toString());
    }
}
