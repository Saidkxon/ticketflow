package com.ticketflow.catalog.events;

import com.ticketflow.catalog.outbox.OutboxRepository;
import com.ticketflow.catalog.web.ApiException;
import com.ticketflow.catalog.web.CorrelationIdFilter;
import java.time.Clock;
import java.time.Instant;
import java.util.UUID;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishing an event (FR-3, ADR-0012). It freezes the layout and prices and records the "layout published"
 * message in the outbox, all in one transaction: either the event is published and the message exists, or
 * neither happened. The compare-and-set UPDATE comes first because it locks the event row, so no layout save can
 * slip in between the readiness check and the commit, and two publish calls cannot both succeed.
 */
@Service
class PublishService {

    private final EventRepository events;
    private final LayoutRepository layouts;
    private final OutboxRepository outbox;
    private final EventService eventService;
    private final TransactionTemplate transaction;
    private final Clock clock;

    PublishService(EventRepository events, LayoutRepository layouts, OutboxRepository outbox,
            EventService eventService, TransactionTemplate transaction, Clock clock) {
        this.events = events;
        this.layouts = layouts;
        this.outbox = outbox;
        this.eventService = eventService;
        this.transaction = transaction;
        this.clock = clock;
    }

    EventDetail publish(UUID ownerId, UUID eventId, long expectedVersion) {
        transaction.executeWithoutResult(status -> {
            long version = events.publishDraft(eventId, ownerId, expectedVersion)
                    .orElseThrow(() -> refusal(ownerId, eventId));
            Instant now = clock.instant();
            if (layouts.countSections(eventId) == 0) {
                throw ApiException.eventNotReady("The event has no seating layout yet.");
            }
            if (!events.findStartsAt(eventId).orElseThrow().isAfter(now)) {
                throw ApiException.eventNotReady("The event has already started.");
            }
            UUID messageId = UUID.randomUUID();
            String json = LayoutPublishedMessage.json(messageId, eventId, version, now, MDC.get(CorrelationIdFilter.MDC_KEY));
            outbox.insert(messageId, "event", eventId, LayoutPublishedMessage.EVENT_TYPE, json);
        });
        return events.findDetail(eventId).orElseThrow();
    }

    private ApiException refusal(UUID ownerId, UUID eventId) {
        EventRepository.EventState state = events.findState(eventId).orElse(null);
        if (state != null && state.ownerId().equals(ownerId) && state.published()) {
            return ApiException.eventAlreadyPublished();
        }
        return eventService.refusalFor(ownerId, eventId);
    }
}
