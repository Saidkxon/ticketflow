package com.ticketflow.identity.sessions;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Protects the cookie endpoints against cross-site request forgery (ADR-0004). A browser attaches
 * the refresh cookie to any request to our address, even one started by another website; SameSite=Strict
 * already stops that, and this is the second layer: the request must carry a custom header
 * (a page on another origin cannot add it without our permission) and an Origin equal to one of
 * the web app's own origins. A request without an Origin is refused: browsers send one on every POST.
 */
@Component
public class BrowserRequestGuard {

    public static final String CLIENT_HEADER = "X-TicketFlow-Client";
    static final String CLIENT_VALUE = "web";

    private static final Logger log = LoggerFactory.getLogger(BrowserRequestGuard.class);

    private final RefreshProperties properties;

    BrowserRequestGuard(RefreshProperties properties) {
        this.properties = properties;
    }

    public void check(HttpServletRequest request) {
        String client = request.getHeader(CLIENT_HEADER);
        String origin = request.getHeader("Origin");
        boolean clientOk = CLIENT_VALUE.equals(client);
        boolean originOk = origin != null && properties.allowedOrigins().contains(origin);
        if (!clientOk || !originOk) {
            // Security event: which rule failed, never the cookie value.
            log.warn("cookie endpoint request refused clientHeaderOk={} originOk={}", clientOk, originOk);
            throw new RequestOriginNotAllowedException();
        }
    }
}
