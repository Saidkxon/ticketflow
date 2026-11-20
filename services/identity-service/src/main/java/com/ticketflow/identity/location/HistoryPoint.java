package com.ticketflow.identity.location;

import java.time.Instant;

public record HistoryPoint(double latitude, double longitude, double accuracyMeters, Instant recordedAt) {
}
