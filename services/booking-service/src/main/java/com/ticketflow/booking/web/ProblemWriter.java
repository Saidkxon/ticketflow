package com.ticketflow.booking.web;

import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import org.slf4j.MDC;

/**
 * Writes an application/problem+json error (ADR-0009) from a servlet filter, where Spring's
 * exception handler is not reached. The text values are fixed strings of this service, never
 * caller input, so they need no escaping.
 */
public final class ProblemWriter {

    private static final String PROBLEM_BASE_URI = "https://api.ticketflow.example/problems/";

    private ProblemWriter() {
    }

    public static void write(HttpServletResponse response, int status, String title, String detail, String code)
            throws IOException {
        String type = PROBLEM_BASE_URI + code.toLowerCase(Locale.ROOT).replace('_', '-');
        StringBuilder json = new StringBuilder()
                .append("{\"type\":\"").append(type).append("\"")
                .append(",\"title\":\"").append(title).append("\"")
                .append(",\"status\":").append(status)
                .append(",\"detail\":\"").append(detail).append("\"")
                .append(",\"code\":\"").append(code).append("\"");
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            json.append(",\"correlationId\":\"").append(correlationId).append("\"");
        }
        json.append("}");
        response.setStatus(status);
        response.setContentType("application/problem+json");
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.getWriter().write(json.toString());
    }
}
