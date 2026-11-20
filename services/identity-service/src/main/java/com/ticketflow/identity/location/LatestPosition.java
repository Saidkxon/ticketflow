package com.ticketflow.identity.location;

import java.time.Instant;
import java.util.UUID;

/** The newest point of one consenting user, for the Admin map. */
public record LatestPosition(UUID userId, String displayName, LocationLevel level, double latitude,
        double longitude, double accuracyMeters, Instant recordedAt) {
}
