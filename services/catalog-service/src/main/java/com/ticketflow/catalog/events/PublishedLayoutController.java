package com.ticketflow.catalog.events;

import static com.ticketflow.catalog.events.OrganizerRequests.etagOf;

import com.ticketflow.catalog.web.ApiException;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * The frozen layout of a published event. Public: the browser draws the seat map from it, and Booking fetches it
 * as its snapshot after a "layout published" event (ADR-0012, part A). A draft or unknown event looks missing.
 */
@RestController
class PublishedLayoutController {

    private static final CacheControl CACHE = CacheControl.maxAge(java.time.Duration.ofHours(1)).cachePublic();

    private final LayoutRepository layouts;

    PublishedLayoutController(LayoutRepository layouts) {
        this.layouts = layouts;
    }

    @GetMapping("/api/v1/events/{eventId}/layout")
    ResponseEntity<PublishedLayout> layout(@PathVariable UUID eventId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        PublishedLayout layout = layouts.findPublished(eventId).orElseThrow(ApiException::eventNotFound);
        String etag = etagOf(layout.layoutVersion());
        if (etag.equals(ifNoneMatch == null ? null : ifNoneMatch.trim())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, etag).cacheControl(CACHE).build();
        }
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etag).cacheControl(CACHE).body(layout);
    }
}
