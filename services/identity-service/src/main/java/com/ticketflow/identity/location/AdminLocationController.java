package com.ticketflow.identity.location;

import com.ticketflow.identity.web.FieldProblem;
import com.ticketflow.identity.web.InvalidRequestException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** Admin-only location reads (ADR-0015, L5). The Admin rule is in SecurityConfig: everything under /admin. */
@RestController
@RequestMapping("/api/v1/admin/location")
public class AdminLocationController {

    static final int DEFAULT_LIMIT = 500;

    private static final Instant FAR_PAST = Instant.parse("1970-01-01T00:00:00Z");
    private static final Instant FAR_FUTURE = Instant.parse("9999-01-01T00:00:00Z");

    private final LocationService service;

    public AdminLocationController(LocationService service) {
        this.service = service;
    }

    @GetMapping("/latest")
    public LatestPositions latest(Authentication authentication) {
        return service.latestOfEveryone(UUID.fromString(authentication.getName()));
    }

    @GetMapping("/users/{userId}/history")
    public UserHistory history(@PathVariable UUID userId,
            @RequestParam(required = false) Instant from,
            @RequestParam(required = false) Instant to,
            @RequestParam(defaultValue = "" + DEFAULT_LIMIT) int limit,
            Authentication authentication) {
        List<FieldProblem> problems = new ArrayList<>();
        if (limit < 1 || limit > LocationService.MAX_HISTORY) {
            problems.add(new FieldProblem("limit", "must be between 1 and " + LocationService.MAX_HISTORY));
        }
        if (from != null && to != null && !from.isBefore(to)) {
            problems.add(new FieldProblem("from", "must be before to"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
        return service.historyOf(UUID.fromString(authentication.getName()), userId,
                from == null ? FAR_PAST : from, to == null ? FAR_FUTURE : to, limit);
    }
}
