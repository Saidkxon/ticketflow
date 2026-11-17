package com.ticketflow.catalog.events;

import com.ticketflow.catalog.web.ApiException;
import java.time.Clock;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * What an organizer may do with events (ADR-0004: ownership is checked here, in the service that owns the
 * data). A new event is always a draft. Only the owner can change it, and only while it is a draft.
 */
@Service
class EventService {

    private final EventRepository events;
    private final Clock clock;

    EventService(EventRepository events, Clock clock) {
        this.events = events;
        this.clock = clock;
    }

    EventDetail create(UUID ownerId, SaveEventRequest request) {
        SaveEventRequest event = request.validated(clock.instant());
        UUID id = UUID.randomUUID();
        events.insertDraft(id, ownerId, event);
        return events.findDetail(id).orElseThrow();
    }

    EventDetail update(UUID ownerId, UUID id, long expectedVersion, SaveEventRequest request) {
        SaveEventRequest event = request.validated(clock.instant());
        if (!events.updateDraft(id, ownerId, expectedVersion, event)) {
            throw whyNotUpdated(ownerId, id);
        }
        return events.findDetail(id).orElseThrow();
    }

    MyEventPage listMine(UUID ownerId, int page, int size) {
        long offset = (long) page * size;
        return new MyEventPage(events.findByOwner(ownerId, size, offset), page, size, events.countByOwner(ownerId));
    }

    /** An event of somebody else looks exactly like a missing one, so the caller cannot find out that it exists. */
    private ApiException whyNotUpdated(UUID ownerId, UUID id) {
        EventRepository.EventState state = events.findState(id).orElse(null);
        if (state == null || !state.ownerId().equals(ownerId)) {
            return ApiException.eventNotFound();
        }
        if (state.published()) {
            return ApiException.eventNotEditable();
        }
        return ApiException.preconditionFailed();
    }
}
