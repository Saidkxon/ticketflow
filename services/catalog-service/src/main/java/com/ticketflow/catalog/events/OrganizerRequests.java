package com.ticketflow.catalog.events;

import com.ticketflow.catalog.security.BearerTokenFilter;
import com.ticketflow.catalog.security.VerifiedToken;
import com.ticketflow.catalog.web.ApiException;
import com.ticketflow.catalog.web.FieldProblem;
import com.ticketflow.catalog.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** What every organizer endpoint needs from the request: who is calling, and which version they are changing. */
final class OrganizerRequests {

    /** The only accepted If-Match form: one strong version tag such as "3" (ADR-0009). */
    private static final Pattern IF_MATCH = Pattern.compile("^\"(\\d{1,18})\"$");

    private OrganizerRequests() {
    }

    /**
     * The token filter puts the verified token on a request only after the token and the ORGANIZER role were
     * checked. A request without it did not pass the filter (for example an unusual spelling of the path), so it
     * is refused here as well.
     */
    static VerifiedToken organizerOf(HttpServletRequest request) {
        if (request.getAttribute(BearerTokenFilter.TOKEN_ATTRIBUTE) instanceof VerifiedToken token) {
            return token;
        }
        throw ApiException.unauthenticated();
    }

    static long versionFrom(String ifMatch) {
        if (ifMatch == null) {
            throw ApiException.preconditionRequired();
        }
        Matcher matcher = IF_MATCH.matcher(ifMatch.trim());
        if (!matcher.matches()) {
            throw new InvalidRequestException(List.of(new FieldProblem("If-Match", "must look like \"3\"")));
        }
        return Long.parseLong(matcher.group(1));
    }

    static String etagOf(long version) {
        return "\"" + version + "\"";
    }
}
