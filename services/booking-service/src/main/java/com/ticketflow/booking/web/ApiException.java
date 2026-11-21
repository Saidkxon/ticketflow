package com.ticketflow.booking.web;

import org.springframework.http.HttpStatus;

/**
 * An expected failure with its own status and stable code; becomes an application/problem+json answer
 * (ADR-0009, part B). The title and detail are fixed texts of this service, never caller input.
 */
public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final String title;

    public ApiException(HttpStatus status, String code, String title, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
        this.title = title;
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

    public static ApiException unauthenticated() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHENTICATED", "Unauthenticated",
                "Authentication is required.");
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
}
