package com.ticketflow.identity.tokens;

/** An access token and how long it stays valid, in seconds. */
public record IssuedToken(String value, long expiresInSeconds) {

    /** The token is a credential and must never end up in a log line. */
    @Override
    public String toString() {
        return "IssuedToken[value=<hidden>, expiresInSeconds=" + expiresInSeconds + "]";
    }
}
