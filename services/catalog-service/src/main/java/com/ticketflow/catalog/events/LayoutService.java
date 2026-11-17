package com.ticketflow.catalog.events;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Saving and reading the venue layout of a draft event (FR-3). Saving replaces the whole layout in one
 * transaction: the event row is claimed first with the same version check as an event edit, so two organizers'
 * saves cannot interleave, and a failure leaves the old layout untouched.
 */
@Service
class LayoutService {

    private final EventRepository events;
    private final LayoutRepository layouts;
    private final EventService eventService;
    private final TransactionTemplate transaction;

    LayoutService(EventRepository events, LayoutRepository layouts, EventService eventService,
            TransactionTemplate transaction) {
        this.events = events;
        this.layouts = layouts;
        this.eventService = eventService;
        this.transaction = transaction;
    }

    VersionedLayout replace(UUID ownerId, UUID eventId, long expectedVersion, LayoutRequest request) {
        LayoutRequest layout = request.validated();
        return transaction.execute(status -> {
            long version = events.bumpVersionForLayout(eventId, ownerId, expectedVersion, layout.currency())
                    .orElseThrow(() -> eventService.refusalFor(ownerId, eventId));
            layouts.deleteLayout(eventId);
            Map<String, UUID> tierIds = new HashMap<>();
            List<LayoutRequest.PriceTier> tiers = layout.priceTiers();
            for (int i = 0; i < tiers.size(); i++) {
                UUID tierId = UUID.randomUUID();
                tierIds.put(tiers.get(i).name(), tierId);
                layouts.insertTier(eventId, tierId, tiers.get(i).name(), tiers.get(i).priceMinor(), i);
            }
            List<LayoutRequest.Section> sections = layout.sections();
            for (int i = 0; i < sections.size(); i++) {
                LayoutRequest.Section section = sections.get(i);
                UUID sectionId = UUID.randomUUID();
                layouts.insertSection(eventId, sectionId, section.name(), i, section.rows(),
                        section.seatsPerRow(), tierIds.get(section.tier()));
                layouts.generateSeats(eventId, sectionId, section.rows(), section.seatsPerRow());
            }
            return new VersionedLayout(layouts.find(eventId), version);
        });
    }

    /** The organizer's own layout. The version is read first, so a racing save can only make it look older. */
    VersionedLayout getOwned(UUID ownerId, UUID eventId) {
        EventDetail event = eventService.getOwned(ownerId, eventId);
        return new VersionedLayout(layouts.find(eventId), event.version());
    }
}
