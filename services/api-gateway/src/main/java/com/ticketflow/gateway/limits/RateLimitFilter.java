package com.ticketflow.gateway.limits;

import com.ticketflow.gateway.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Rate limit per client address (ADR-0013, rule G3). The client address is the one that the web
 * server in front (nginx) writes into X-Forwarded-For. That is only safe because nothing but that
 * web server can reach the gateway (rule G10); a caller who could reach the gateway directly could
 * choose any address. The header is checked for shape so it cannot be used to fill memory.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
class RateLimitFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(RateLimitFilter.class);
    private static final Pattern ADDRESS_SHAPE = Pattern.compile("^[0-9a-fA-F:.]{2,45}$");
    private static final String AUTH_PREFIX = "/api/v1/auth/";

    private final FixedWindowLimiter limiter;
    private final LimitsProperties limits;

    RateLimitFilter(FixedWindowLimiter limiter, LimitsProperties limits) {
        this.limiter = limiter;
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        boolean auth = request.getRequestURI().startsWith(AUTH_PREFIX);
        String group = auth ? "auth" : "default";
        int limit = auth ? limits.authPerMinute() : limits.defaultPerMinute();

        FixedWindowLimiter.Decision decision = limiter.tryAcquire(group + ":" + clientAddress(request), limit);
        if (!decision.allowed()) {
            log.warn("rate limit exceeded group={}", group);
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            ProblemWriter.write(response, 429, "Too many requests",
                    "Too many requests. Try again later.", "RATE_LIMITED");
            return;
        }
        chain.doFilter(request, response);
    }

    static String clientAddress(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null) {
            String value = forwarded.trim();
            if (ADDRESS_SHAPE.matcher(value).matches()) {
                return value;
            }
        }
        return request.getRemoteAddr();
    }
}
