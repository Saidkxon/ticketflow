package com.ticketflow.identity.location;

/** The answer was given for another version of the consent text. Becomes 409 CONSENT_TEXT_OUTDATED. */
public class ConsentTextOutdatedException extends RuntimeException {
    public ConsentTextOutdatedException() {
        super("Consent text outdated");
    }
}
