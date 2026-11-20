package com.ticketflow.identity.location;

/** Unknown user, or a user who does not consent to tracking: the same 404 LOCATION_NOT_AVAILABLE for both. */
public class LocationNotAvailableException extends RuntimeException {
    public LocationNotAvailableException() {
        super("Location not available");
    }
}
