package com.ticketflow.booking.holds;

import com.ticketflow.booking.security.BearerTokenFilter;
import com.ticketflow.booking.security.VerifiedToken;
import com.ticketflow.booking.web.ApiException;
import com.ticketflow.booking.web.FieldProblem;
import com.ticketflow.booking.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.util.List;
import java.util.UUID;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

/**
 * Holds seats for the signed-in customer (ADR-0012, FR-6). The token filter has already refused a request without a
 * valid token; the user ID comes from that verified token and never from the request body. The answer is personal, so
 * it is never stored by a cache.
 */
@RestController
class HoldController {

    private final HoldService holds;

    HoldController(HoldService holds) {
        this.holds = holds;
    }

    @PostMapping("/api/v1/bookings")
    ResponseEntity<BookingResponse> hold(HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody HoldRequest body) {
        if (!(request.getAttribute(BearerTokenFilter.TOKEN_ATTRIBUTE) instanceof VerifiedToken token)) {
            throw ApiException.unauthenticated();
        }
        UUID key = key(idempotencyKey);
        if (body.eventId() == null) {
            throw new InvalidRequestException(List.of(new FieldProblem("eventId", "is required")));
        }
        if (body.seatIds() == null) {
            throw new InvalidRequestException(List.of(new FieldProblem("seatIds", "is required")));
        }
        BookingResponse booking = holds.hold(token.userId(), key, body.eventId(), body.seatIds());
        return ResponseEntity.status(HttpStatus.CREATED).cacheControl(CacheControl.noStore()).body(booking);
    }

    private static UUID key(String header) {
        if (header == null || header.isBlank()) {
            throw ApiException.idempotencyKeyRequired();
        }
        try {
            return UUID.fromString(header.trim());
        } catch (IllegalArgumentException e) {
            throw new InvalidRequestException(List.of(new FieldProblem("Idempotency-Key", "must be a UUID")));
        }
    }
}
