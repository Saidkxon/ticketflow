package com.ticketflow.identity.location;

import java.util.List;
import java.util.UUID;

/** A user's points, newest first. */
public record UserHistory(UUID userId, String displayName, LocationLevel level, List<HistoryPoint> items) {
}
