package com.ticketflow.catalog.security;

import com.ticketflow.catalog.web.ProblemWriter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Catalog checks access tokens itself (ADR-0004, Part B): the gateway checks them early, but a request that
 * reaches this service some other way must not be trusted.
 *
 * <p>Reading the event list is public. Everything else under /api/v1/events, and "my events", needs a valid
 * token with the ORGANIZER role; the service that owns the data decides about roles. The rule is
 * "everything that is not a plain read", so a new write endpoint is protected without anybody remembering to
 * list it. The verified token is put on the request only after both checks passed, and the controllers refuse
 * a request that has none, so an unusual spelling of a path cannot skip the check.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerTokenFilter extends OncePerRequestFilter {

    /** The request attribute that holds the {@link VerifiedToken} of an authenticated organizer. */
    public static final String TOKEN_ATTRIBUTE = BearerTokenFilter.class.getName() + ".token";

    static final String ORGANIZER = "ORGANIZER";

    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);
    private static final String PREFIX = "Bearer ";
    private static final String EVENTS = "/api/v1/events";
    private static final String MY_EVENTS = "/api/v1/events/mine";

    private final AccessTokenVerifier verifier;

    BearerTokenFilter(AccessTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!needsOrganizer(request.getMethod(), request.getRequestURI())) {
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
        if (!token.roles().contains(ORGANIZER)) {
            log.warn("organizer role required userId={}", token.userId());
            ProblemWriter.write(response, 403, "Forbidden",
                    "You do not have permission to do this.", "FORBIDDEN");
            return;
        }
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        chain.doFilter(request, response);
    }

    /** True for every request under /api/v1/events except a plain read (GET or HEAD), and for "my events". */
    static boolean needsOrganizer(String method, String path) {
        if (!path.equals(EVENTS) && !path.startsWith(EVENTS + "/")) {
            return false;
        }
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        return !read || path.equals(MY_EVENTS);
    }
}
