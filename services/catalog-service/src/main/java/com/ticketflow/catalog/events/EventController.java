package com.ticketflow.catalog.events;

import static com.ticketflow.catalog.events.OrganizerRequests.etagOf;
import static com.ticketflow.catalog.events.OrganizerRequests.organizerOf;
import static com.ticketflow.catalog.events.OrganizerRequests.versionFrom;

import com.ticketflow.catalog.security.VerifiedToken;
import com.ticketflow.catalog.web.FieldProblem;
import com.ticketflow.catalog.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EventController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final EventRepository events;
    private final EventService service;

    EventController(EventRepository events, EventService service) {
        this.events = events;
        this.service = service;
    }

    @GetMapping("/api/v1/events")
    EventPage listEvents(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        checkPaging(page, size);
        long offset = (long) page * size;
        return new EventPage(events.findPublished(size, offset), page, size, events.countPublished());
    }

    /** The signed-in organizer's own events, drafts included. The token filter has already checked the role. */
    @GetMapping("/api/v1/events/mine")
    MyEventPage listMyEvents(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size, HttpServletRequest request) {
        VerifiedToken organizer = organizerOf(request);
        checkPaging(page, size);
        return service.listMine(organizer.userId(), page, size);
    }

    /** One of the caller's own events, drafts included. The ETag is the version to send back in If-Match. */
    @GetMapping("/api/v1/events/mine/{eventId}")
    ResponseEntity<EventDetail> myEvent(@PathVariable UUID eventId, HttpServletRequest request) {
        VerifiedToken organizer = organizerOf(request);
        EventDetail event = service.getOwned(organizer.userId(), eventId);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etagOf(event.version())).body(event);
    }

    @PostMapping("/api/v1/events")
    ResponseEntity<EventDetail> createEvent(@RequestBody SaveEventRequest body, HttpServletRequest request) {
        VerifiedToken organizer = organizerOf(request);
        EventDetail created = service.create(organizer.userId(), body);
        return ResponseEntity.created(URI.create("/api/v1/events/" + created.id()))
                .header(HttpHeaders.ETAG, etagOf(created.version()))
                .body(created);
    }

    @PutMapping("/api/v1/events/{eventId}")
    ResponseEntity<EventDetail> updateEvent(@PathVariable UUID eventId,
            @RequestHeader(value = HttpHeaders.IF_MATCH, required = false) String ifMatch,
            @RequestBody SaveEventRequest body, HttpServletRequest request) {
        VerifiedToken organizer = organizerOf(request);
        long expectedVersion = versionFrom(ifMatch);
        EventDetail updated = service.update(organizer.userId(), eventId, expectedVersion, body);
        return ResponseEntity.ok().header(HttpHeaders.ETAG, etagOf(updated.version())).body(updated);
    }

    private static void checkPaging(int page, int size) {
        List<FieldProblem> problems = new ArrayList<>();
        if (page < 0) {
            problems.add(new FieldProblem("page", "must be 0 or greater"));
        }
        if (size < 1 || size > MAX_SIZE) {
            problems.add(new FieldProblem("size", "must be between 1 and " + MAX_SIZE));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
    }
}
