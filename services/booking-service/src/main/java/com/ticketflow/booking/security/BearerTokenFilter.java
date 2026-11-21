package com.ticketflow.booking.security;

import com.ticketflow.booking.web.ProblemWriter;
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
 * Booking checks access tokens itself (ADR-0004, Part B): the gateway checks them early, but a request that
 * reaches this service some other way must not be trusted.
 *
 * <p>Everything under /api/v1 needs a valid token (any signed-in user: seats and bookings belong to the person who
 * made them, and the service that owns the data decides who may see what). The single public exception is the plain
 * read of an event's seat availability, which anybody may poll (ADR-0011, ADR-0013). The rule is "everything that is
 * not listed as public", so a new endpoint is protected without anybody remembering to list it. The verified token is
 * put on the request only after the check passed, and controllers refuse a request that has none, so an unusual
 * spelling of a path cannot skip the check.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class BearerTokenFilter extends OncePerRequestFilter {

    /** The request attribute that holds the {@link VerifiedToken} of the signed-in user. */
    public static final String TOKEN_ATTRIBUTE = BearerTokenFilter.class.getName() + ".token";

    private static final Logger log = LoggerFactory.getLogger(BearerTokenFilter.class);
    private static final String PREFIX = "Bearer ";
    private static final String API = "/api/v1";
    private static final String EVENTS = "/api/v1/events/";
    private static final String AVAILABILITY = "/availability";

    private final AccessTokenVerifier verifier;

    BearerTokenFilter(AccessTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!needsToken(request.getMethod(), request.getRequestURI())) {
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
        request.setAttribute(TOKEN_ATTRIBUTE, token);
        chain.doFilter(request, response);
    }

    /** True for every request under /api/v1 except a plain read (GET or HEAD) of /api/v1/events/{eventId}/availability. */
    static boolean needsToken(String method, String path) {
        if (!path.equals(API) && !path.startsWith(API + "/")) {
            return false;
        }
        boolean read = "GET".equals(method) || "HEAD".equals(method);
        return !(read && isAvailability(path));
    }

    private static boolean isAvailability(String path) {
        if (!path.startsWith(EVENTS) || !path.endsWith(AVAILABILITY)) {
            return false;
        }
        String eventId = path.substring(EVENTS.length(), path.length() - AVAILABILITY.length());
        return !eventId.isEmpty() && eventId.indexOf('/') < 0;
    }
}
