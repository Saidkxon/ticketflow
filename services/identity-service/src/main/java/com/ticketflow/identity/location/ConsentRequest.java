package com.ticketflow.identity.location;

/** The person's answer. textVersion is the version of the text they saw. */
public record ConsentRequest(LocationLevel level, Integer textVersion) {
}
