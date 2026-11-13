package com.ticketflow.catalog.events;

import com.ticketflow.catalog.web.FieldProblem;
import com.ticketflow.catalog.web.InvalidRequestException;
import java.util.ArrayList;
import java.util.List;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
class EventController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final EventRepository events;

    EventController(EventRepository events) {
        this.events = events;
    }

    @GetMapping("/api/v1/events")
    EventPage listEvents(@RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
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
        long offset = (long) page * size;
        return new EventPage(events.findPublished(size, offset), page, size, events.countPublished());
    }
}
