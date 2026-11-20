package com.ticketflow.identity.location;

/** A position was sent without a current FULL or MINIMUM answer. Becomes 403 LOCATION_CONSENT_REQUIRED. */
public class LocationConsentRequiredException extends RuntimeException {
    public LocationConsentRequiredException() {
        super("Location consent required");
    }
}
