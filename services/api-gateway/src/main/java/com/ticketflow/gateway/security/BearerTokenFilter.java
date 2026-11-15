package com.ticketflow.gateway.security;

import com.ticketflow.gateway.limits.FixedWindowLimiter;
import com.ticketflow.gateway.limits.LimitsProperties;
import com.ticketflow.gateway.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * The early token check (ADR-0004, Part B): on the paths that need a signed-in user, a request without
 * a valid access token is stopped here with a plain 401, before any service is called. The token is
 * forwarded unchanged, because every service checks it again and decides about roles itself.
 * A valid token also gets its own request counter per user (ADR-0013, rule G3).
 * A path is public unless it is listed here, so a new protected area must be added to this list.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 30)
class BearerTokenFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);
    private static final String PREFIX = "Bearer ";

    /** Path prefixes that need a valid access token. Add the booking and payment prefixes when those services get routes. */
    static final List<String> PROTECTED_PREFIXES = List.of("/api/v1/users");

    private final AccessTokenVerifier verifier;
    private final FixedWindowLimiter limiter;
    private final LimitsProperties limits;

    BearerTokenFilter(AccessTokenVerifier verifier, FixedWindowLimiter limiter, LimitsProperties limits) {
        this.verifier = verifier;
        this.limiter = limiter;
        this.limits = limits;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!needsToken(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        VerifiedToken token;
        try {
            if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                throw new InvalidAccessTokenException();
            }
            token = verifier.verify(header.substring(PREFIX.length()).trim());
        } catch (InvalidAccessTokenException e) {
            // Security event (ADR-0004): no token and no reason in the log line.
            log.warn("access token missing or rejected");
            ProblemWriter.write(response, 401, "Unauthenticated", "Authentication is required.", "UNAUTHENTICATED");
            return;
        } catch (KeysUnavailableException e) {
            log.error("cannot fetch the token keys from Identity", e);
            ProblemWriter.write(response, 503, "Service unavailable",
                    "The service is temporarily unavailable.", "UPSTREAM_UNAVAILABLE");
            return;
        }

        FixedWindowLimiter.Decision decision = limiter.tryAcquire("user:" + token.userId(), limits.userPerMinute());
        if (!decision.allowed()) {
            log.warn("rate limit exceeded group=user");
            response.setHeader("Retry-After", String.valueOf(decision.retryAfterSeconds()));
            ProblemWriter.write(response, 429, "Too many requests",
                    "Too many requests. Try again later.", "RATE_LIMITED");
            return;
        }
        chain.doFilter(request, response);
    }

    static boolean needsToken(String path) {
        for (String prefix : PROTECTED_PREFIXES) {
            if (path.equals(prefix) || path.startsWith(prefix + "/")) {
                return true;
            }
        }
        return false;
    }
}
