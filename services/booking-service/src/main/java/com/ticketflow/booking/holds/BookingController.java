package com.ticketflow.booking.holds;

import com.ticketflow.booking.security.BearerTokenFilter;
import com.ticketflow.booking.security.VerifiedToken;
import com.ticketflow.booking.web.ApiException;
import com.ticketflow.booking.web.FieldProblem;
import com.ticketflow.booking.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Reads and releases the signed-in customer's own bookings (FR-6, ADR-0012). The customer is the user of the verified
 * token. A booking of somebody else is answered exactly like one that does not exist (404), so nobody can find out
 * which booking IDs exist. Every answer is personal and is never stored by a cache.
 */
@RestController
class BookingController {

    static final int DEFAULT_SIZE = 20;
    static final int MAX_SIZE = 50;

    private final BookingReader reader;
    private final ReleaseService releases;

    BookingController(BookingReader reader, ReleaseService releases) {
        this.reader = reader;
        this.releases = releases;
    }

    @GetMapping("/api/v1/bookings")
    ResponseEntity<BookingPage> list(HttpServletRequest request,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "" + DEFAULT_SIZE) int size) {
        VerifiedToken token = signedIn(request);
        checkPaging(page, size);
        BookingPage result = new BookingPage(reader.pageOf(token.userId(), size, (long) page * size), page, size,
                reader.countOf(token.userId()));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(result);
    }

    @GetMapping("/api/v1/bookings/{bookingId}")
    ResponseEntity<BookingResponse> get(HttpServletRequest request, @PathVariable String bookingId) {
        VerifiedToken token = signedIn(request);
        BookingResponse booking = reader.findOwned(id(bookingId), token.userId())
                .orElseThrow(ApiException::bookingNotFound);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(booking);
    }

    @PostMapping("/api/v1/bookings/{bookingId}/release")
    ResponseEntity<BookingResponse> release(HttpServletRequest request, @PathVariable String bookingId) {
        VerifiedToken token = signedIn(request);
        BookingResponse booking = releases.release(token.userId(), id(bookingId));
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(booking);
    }

    private static VerifiedToken signedIn(HttpServletRequest request) {
        if (request.getAttribute(BearerTokenFilter.TOKEN_ATTRIBUTE) instanceof VerifiedToken token) {
            return token;
        }
        throw ApiException.unauthenticated();
    }

    private static UUID id(String text) {
        try {
            return UUID.fromString(text);
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(List.of(new FieldProblem("bookingId", "must be a UUID")));
        }
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
