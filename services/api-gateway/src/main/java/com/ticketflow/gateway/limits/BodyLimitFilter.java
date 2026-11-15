package com.ticketflow.gateway.limits;

import com.ticketflow.gateway.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.Set;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Maximum request body size (ADR-0013, rule G7). A body that declares a size above the limit is
 * refused with 413 before any byte is read. A body of unknown size (chunked) is refused with 411,
 * because its size could not be checked here; browsers and normal clients send a size for the small
 * JSON bodies of this API.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 20)
class BodyLimitFilter extends OncePerRequestFilter {

    private static final Set<String> METHODS_WITH_BODY = Set.of("POST", "PUT", "PATCH");

    private final long maxBytes;

    BodyLimitFilter(LimitsProperties limits) {
        this.maxBytes = limits.maxBodySize().toBytes();
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (METHODS_WITH_BODY.contains(request.getMethod())) {
            if (request.getHeader("Transfer-Encoding") != null) {
                ProblemWriter.write(response, 411, "Length required",
                        "The request must state the size of its body.", "LENGTH_REQUIRED");
                return;
            }
            if (request.getContentLengthLong() > maxBytes) {
                ProblemWriter.write(response, 413, "Payload too large",
                        "The request body is too large.", "PAYLOAD_TOO_LARGE");
                return;
            }
        }
        chain.doFilter(request, response);
    }
}
