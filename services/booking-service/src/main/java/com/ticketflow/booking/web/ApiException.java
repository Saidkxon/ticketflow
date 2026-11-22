package com.ticketflow.booking.web;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;

/**
 * An expected failure with its own status and stable code; becomes an application/problem+json answer
 * (ADR-0009, part B). The title and detail are fixed texts of this service, never caller input.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;
    private final Map<String, Object> properties;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        this(status, code, title, detail, Map.of());
    }

    /** {@code properties} are extra fields of the problem body (ADR-0009), such as the list of unavailable seats. */
    public ApiException(HttpStatus status, String code, String title, String detail, Map<String, Object> properties) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
        this.properties = Map.copyOf(properties);
    }

    public HttpStatus status() {
        return status;
    }

    public String code() {
        return code;
    }

    public String title() {
        return title;
    }

    public Map<String, Object> properties() {
        return properties;
    }

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Unauthenticated",
                "Authentication is required.");
    }

    /** The event has no seat inventory (yet): it is unknown, still being set up, or not published. */
    public static ApiException notOnSale() {
        return new ApiException(HttpStatus.NOT_FOUND, "EVENT_NOT_ON_SALE", "Event is not on sale",
                "The event is not on sale (yet).");
    }

    /** Also used when the event belongs to somebody else: the caller must not learn that it exists. */
    public static ApiException eventNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "The event was not found.");
    }

    public static ApiException eventNotEditable() {
        return new ApiException(HttpStatus.CONFLICT, "EVENT_NOT_EDITABLE", "Event cannot be edited",
                "A published event can no longer be edited.");
    }

    public static ApiException eventAlreadyPublished() {
        return new ApiException(HttpStatus.CONFLICT, "EVENT_ALREADY_PUBLISHED", "Event already published",
                "The event is already published.");
    }

    /** The detail is one of the fixed texts of the publish rules, never caller input. */
    public static ApiException eventNotReady(String detail) {
        return new ApiException(HttpStatus.CONFLICT, "EVENT_NOT_READY", "Event is not ready to publish", detail);
    }

    public static ApiException preconditionRequired() {
        return new ApiException(HttpStatus.PRECONDITION_REQUIRED, "PRECONDITION_REQUIRED",
                "Precondition required", "Send the version you are changing in the If-Match header.");
    }

    public static ApiException preconditionFailed() {
        return new ApiException(HttpStatus.PRECONDITION_FAILED, "PRECONDITION_FAILED", "Precondition failed",
                "The event was changed by someone else. Load it again and retry.");
    }

    public static ApiException idempotencyKeyRequired() {
        return new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency key required",
                "Send an Idempotency-Key header with a UUID of your choice.");
    }

    public static ApiException idempotencyKeyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                "This Idempotency-Key was already used for a different request.");
    }

    /** Only if a stored key has no booking yet, which the transaction rules should make impossible. */
    public static ApiException requestInProgress() {
        return new ApiException(HttpStatus.CONFLICT, "REQUEST_IN_PROGRESS", "Request in progress",
                "The first request with this Idempotency-Key is still running. Try again shortly.");
    }

    /** ADR-0012 and ADR-0009: the answer to a hold that cannot be given names the seats that are not free. */
    public static ApiException seatsUnavailable(List<UUID> unavailableSeatIds) {
        return new ApiException(HttpStatus.CONFLICT, "SEATS_UNAVAILABLE", "Seats are not available",
                "One or more of the seats are not available. Nothing was held.",
                Map.of("unavailableSeatIds", List.copyOf(unavailableSeatIds)));
    }

    /** The seats were being changed by other requests for longer than the lock timeout (ADR-0012). */
    public static ApiException seatsBusy() {
        return new ApiException(HttpStatus.CONFLICT, "SEATS_BUSY", "Seats are busy",
                "Other customers are choosing the same seats right now. Try again in a moment.");
    }

    public static ApiException activeHoldExists() {
        return new ApiException(HttpStatus.CONFLICT, "ACTIVE_HOLD_EXISTS", "You already hold seats",
                "You already hold seats for this event. Finish or release that booking first.");
    }

    /** Also used for a booking of somebody else: the caller must not learn that it exists. */
    public static ApiException bookingNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "The booking was not found.");
    }

    /** Only a hold that is still running can be released; an expired one has already lost its seats. */
    public static ApiException bookingNotActive() {
        return new ApiException(HttpStatus.CONFLICT, "BOOKING_NOT_ACTIVE", "Booking is not active",
                "The hold of this booking has ended, so there is nothing to release.");
    }
}
