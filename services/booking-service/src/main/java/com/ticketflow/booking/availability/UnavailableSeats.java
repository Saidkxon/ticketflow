package com.ticketflow.booking.availability;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/** Where the list of unavailable seats comes from. A small interface so the cache can be tested without a database. */
interface UnavailableSeats {

    /**
     * The IDs of the seats of this event that cannot be taken right now, sorted; empty when the event is not on sale
     * (it has no seat inventory yet).
     */
    Optional<List<UUID>> unavailableSeatIds(UUID eventId);
}
