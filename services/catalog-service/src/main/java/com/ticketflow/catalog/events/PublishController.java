package com.ticketflow.catalog.events;

import static com.ticketflow.catalog.events.OrganizerRequests.etagOf;
import static com.ticketflow.catalog.events.OrganizerRequests.organizerOf;
import static com.ticketflow.catalog.events.OrganizerRequests.versionFrom;

import com.ticketflow.catalog.security.VerifiedToken;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class PublishController {

    private final PublishService service;

    PublishController(PublishService service) {
        this.service = service;
    }

    /** Publishes the caller's draft. Needs If-Match with the version the organizer last saw (ADR-0009). */
    @PostMapping("/api/v1/events/{eventId}/publish")
    ResponseEntity<EventDetail> publish(@PathVariable UUID eventId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            HttpServletRequest request) {
        VerifiedToken organizer = organizerOf(request);
        long expectedVersion = versionFrom(ifMatch);
        EventDetail published = service.publish(organizer.userId(), eventId, expectedVersion);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etagOf(published.version())).body(published);
    }
}
