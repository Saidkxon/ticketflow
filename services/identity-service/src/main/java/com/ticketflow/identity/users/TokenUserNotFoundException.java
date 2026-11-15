package com.ticketflow.identity.users;

/** The token is valid but its user no longer exists. Becomes 401 UNAUTHENTICATED. */
public class TokenUserNotFoundException extends RuntimeException {

    public TokenUserNotFoundException() {
        super("User of the token not found");
    }
}
