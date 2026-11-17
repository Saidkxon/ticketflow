package com.ticketflow.catalog.events;

import static com.ticketflow.catalog.events.OrganizerRequests.etagOf;
import static com.ticketflow.catalog.events.OrganizerRequests.organizerOf;
import static com.ticketflow.catalog.events.OrganizerRequests.versionFrom;

import com.ticketflow.catalog.security.VerifiedToken;
import jakarta.servlet.http.HttpServletRequest;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class LayoutController {

    private final LayoutService service;

    LayoutController(LayoutService service) {
        this.service = service;
    }

    /** Replaces the whole layout of the caller's draft event. Needs If-Match, like an event edit (ADR-0009). */
    @PutMapping("/api/v1/events/{eventId}/layout")
    ResponseEntity<LayoutDetail> replaceLayout(@PathVariable UUID eventId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody LayoutRequest body, HttpServletRequest request) {
        VerifiedToken organizer = organizerOf(request);
        long expectedVersion = versionFrom(ifMatch);
        VersionedLayout saved = service.replace(organizer.userId(), eventId, expectedVersion, body);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etagOf(saved.version())).body(saved.layout());
    }

    /** The caller's own layout, also while the event is a draft. The ETag is the event version to send back. */
    @GetMapping("/api/v1/events/mine/{eventId}/layout")
    ResponseEntity<LayoutDetail> myLayout(@PathVariable UUID eventId, HttpServletRequest request) {
        VerifiedToken organizer = organizerOf(request);
        VersionedLayout found = service.getOwned(organizer.userId(), eventId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etagOf(found.version())).body(found.layout());
    }
}
