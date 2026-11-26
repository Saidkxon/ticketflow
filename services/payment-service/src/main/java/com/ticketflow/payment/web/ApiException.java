package com.ticketflow.payment.web;

import java.util.Map;
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

    /** {@code properties} are extra fields of the problem body (ADR-0009). */
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

    public static ApiException idempotencyKeyRequired() {
        return new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_REQUIRED", "Idempotency key required",
                "Send an Idempotency-Key header: the booking ID, a colon and the attempt number.");
    }

    public static ApiException idempotencyKeyInvalid() {
        return new ApiException(HttpStatus.BAD_REQUEST, "IDEMPOTENCY_KEY_INVALID", "Idempotency key invalid",
                "The Idempotency-Key must be the booking ID, a colon and the attempt number, for example <uuid>:1.");
    }

    public static ApiException idempotencyKeyReused() {
        return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "IDEMPOTENCY_KEY_REUSED", "Idempotency key reused",
                "This Idempotency-Key was already used for a different request.");
    }

    public static ApiException paymentNotFound() {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", "Not found", "The payment was not found.");
    }

    /** A valid service token that does not grant what this endpoint needs. */
    public static ApiException insufficientScope() {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", "Forbidden",
                "The token does not allow this request.");
    }
}
