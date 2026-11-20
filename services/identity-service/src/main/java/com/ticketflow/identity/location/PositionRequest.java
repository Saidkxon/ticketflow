package com.ticketflow.identity.location;

import java.time.Instant;

/** One point from the browser. The numbers are boxed so a missing field is reported, not read as 0. */
public record PositionRequest(Double latitude, Double longitude, Double accuracyMeters, Instant recordedAt) {
}
