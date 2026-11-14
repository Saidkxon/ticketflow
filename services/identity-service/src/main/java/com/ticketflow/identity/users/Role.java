package com.ticketflow.identity.users;

/** The three roles of ADR-0004. Registration always creates a CUSTOMER. */
public enum Role {
    CUSTOMER,
    ORGANIZER,
    ADMIN
}
