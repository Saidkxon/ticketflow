package com.ticketflow.identity.auth;

/** Body of a successful login (contracts/identity.openapi.yaml, schema TokenResponse). */
public record TokenResponse(String accessToken, String tokenType, long expiresIn) {

    /** The access token is a credential and must never end up in a log line. */
    @Override
    public String toString() {
        return "TokenResponse[accessToken=<hidden>, tokenType=" + tokenType + ", expiresIn=" + expiresIn + "]";
    }
}
