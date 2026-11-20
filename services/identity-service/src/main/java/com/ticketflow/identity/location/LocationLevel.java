package com.ticketflow.identity.location;

/** The answer to the consent question (ADR-0015). FULL keeps history 30 days, MINIMUM 5 days, OFF keeps nothing. */
public enum LocationLevel {
    FULL, MINIMUM, OFF;

    boolean tracks() {
        return this != OFF;
    }
}
