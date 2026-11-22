package com.ticketflow.booking.availability;

import com.ticketflow.booking.web.ApiException;
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
 * Which seats of an event cannot be taken right now (ADR-0011, ADR-0012). Public: it contains seat IDs only, no
 * personal data (ADR-0004). The seat map polls it; an unchanged list answers 304 with no body. The map is a hint, not
 * a promise: the server decides every hold.
 */
@RestController
class AvailabilityController {

    /** Always ask again (the answer is a conditional request, cheap when nothing changed) instead of guessing for how long it is good. */
    private static final CacheControl REVALIDATE = CacheControl.noCache();

    private final AvailabilityService availability;

    AvailabilityController(AvailabilityService availability) {
        this.availability = availability;
    }

    @GetMapping("/api/v1/events/{eventId}/availability")
    ResponseEntity<AvailabilityResponse> availability(@PathVariable UUID eventId,
            @RequestHeader(value = HttpHeaders.IF_NONE_MATCH, required = false) String ifNoneMatch) {
        AvailabilityService.Snapshot snapshot = availability.find(eventId).orElseThrow(ApiException::notOnSale);
        // Sent as a weak tag: the meaning is the same but the bytes may be compressed, and Tomcat does not
        // compress a response that carries a strong tag. A weak tag is enough for If-None-Match.
        String tag = "W/" + snapshot.etag();
        if (AvailabilityService.etagMatches(ifNoneMatch, snapshot.etag())) {
            return ResponseEntity.status(HttpStatus.NOT_MODIFIED)
                    .header(HttpHeaders.ETAG, tag).cacheControl(REVALIDATE).build();
        }
        return ResponseEntity.ok().header(HttpHeaders.ETAG, tag).cacheControl(REVALIDATE)
                .body(new AvailabilityResponse(snapshot.eventId(), snapshot.unavailableSeatIds()));
    }
}
