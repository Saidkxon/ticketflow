package com.ticketflow.identity.sessions;

/** The custom header is missing or the Origin is not the web app's. Becomes 403 REQUEST_ORIGIN_NOT_ALLOWED. */
public class RequestOriginNotAllowedException extends RuntimeException {

    public RequestOriginNotAllowedException() {
        super("Request origin not allowed");
    }
}
