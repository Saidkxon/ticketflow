package com.ticketflow.identity.sessions;

import com.ticketflow.identity.tokens.IssuedToken;

/** What a login or a refresh produces: a new access token and the next refresh token. Both are credentials. */
public record Session(IssuedToken accessToken, String refreshToken) {

    @Override
    public String toString() {
        return "Session[accessToken=<hidden>, refreshToken=<hidden>]";
    }
}
