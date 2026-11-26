package com.ticketflow.payment.security;

import com.ticketflow.payment.web.ApiException;
import com.ticketflow.payment.web.ProblemWriter;
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
 * Payment has no public endpoints: the gateway does not route to it (ADR-0002, ADR-0013). Its endpoints are internal,
 * live under {@code /internal/v1} and are called by other services with a service token (ADR-0004, Part D).
 *
 * <p>Everything under {@code /internal/v1} needs a valid service token. The rule is "everything under the prefix", with
 * no list of exceptions, so a new endpoint is protected without anybody remembering to list it. The verified token is
 * put on the request only after the check passed, and controllers ask for it with {@link #require}, which refuses a
 * request that has none, so an unusual spelling of a path cannot skip the check. What a token may do is decided per
 * endpoint with {@link #requireScope}.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ServiceTokenFilter extends OncePerRequestFilter {

    /** The request attribute that holds the {@link ServiceToken} of the calling service. */
    public static final String TOKEN_ATTRIBUTE = ServiceTokenFilter.class.getName() + ".token";

    private static final Logger log = LoggerFactory.getLogger(ServiceTokenFilter.class);
    private static final String PREFIX = "Bearer ";
    private static final String INTERNAL = "/internal/v1";

    private final ServiceTokenVerifier verifier;

    ServiceTokenFilter(ServiceTokenVerifier verifier) {
        this.verifier = verifier;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
            FilterChain chain) throws ServletException, IOException {
        if (!needsToken(request.getRequestURI())) {
            chain.doFilter(request, response);
            return;
        }
        String header = request.getHeader("Authorization");
        ServiceToken token;
        try {
            if (header == null || !header.regionMatches(true, 0, PREFIX, 0, PREFIX.length())) {
                throw new InvalidAccessTokenException();
            }
            token = verifier.verify(header.substring(PREFIX.length()).trim());
        } catch (InvalidAccessTokenException e) {
            // Security event (ADR-0004): no token and no reason in the log line.
            log.warn("service token missing or rejected");
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

    /** True for {@code /internal/v1} and everything below it. */
    static boolean needsToken(String path) {
        return path.equals(INTERNAL) || path.startsWith(INTERNAL + "/");
    }

    /** The verified token of this request. A controller calls this first; a request without one is refused. */
    public static ServiceToken require(HttpServletRequest request) {
        Object token = request.getAttribute(TOKEN_ATTRIBUTE);
        if (token instanceof ServiceToken verified) {
            return verified;
        }
        throw ApiException.unauthenticated();
    }

    /** Like {@link #require}, and the token must also carry this scope (a valid token for another purpose is a 403). */
    public static ServiceToken requireScope(HttpServletRequest request, String scope) {
        ServiceToken token = require(request);
        if (!token.allows(scope)) {
            throw ApiException.insufficientScope();
        }
        return token;
    }
}
