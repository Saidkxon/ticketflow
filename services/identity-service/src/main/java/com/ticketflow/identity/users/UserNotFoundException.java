package com.ticketflow.identity.users;

/** No user with this ID. Becomes 404 USER_NOT_FOUND. */
public class UserNotFoundException extends RuntimeException {

    public UserNotFoundException() {
        super("User not found");
    }
}
