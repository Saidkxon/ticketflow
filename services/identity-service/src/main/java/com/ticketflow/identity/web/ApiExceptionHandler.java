package com.ticketflow.identity.web;

import com.ticketflow.identity.auth.EmailAlreadyRegisteredException;
import com.ticketflow.identity.auth.InvalidCredentialsException;
import com.ticketflow.identity.auth.TooManyAttemptsException;
import com.ticketflow.identity.location.ConsentTextOutdatedException;
import com.ticketflow.identity.location.LocationConsentRequiredException;
import com.ticketflow.identity.location.LocationNotAvailableException;
import com.ticketflow.identity.sessions.InvalidRefreshTokenException;
import com.ticketflow.identity.sessions.RefreshCookies;
import com.ticketflow.identity.sessions.RequestOriginNotAllowedException;
import com.ticketflow.identity.users.TokenUserNotFoundException;
import com.ticketflow.identity.users.UserNotFoundException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import java.net.URI;
import java.util.Locale;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.context.request.WebRequest;
import org.springframework.web.servlet.mvc.method.annotation.ResponseEntityExceptionHandler;

/**
 * Every error leaves this service as application/problem+json (RFC 9457) with a stable
 * "code" and the "correlationId" (ADR-0009). Spring's own exceptions (unknown path,
 * wrong method, bad parameter) are enriched here; anything unexpected becomes a generic
 * 500 whose details go to the log and never to the caller.
 */
@RestControllerAdvice
public class ApiExceptionHandler extends ResponseEntityExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
    private static final String PROBLEM_BASE_URI = "https://api.ticketflow.example/problems/";

    private final RefreshCookies refreshCookies;

    public ApiExceptionHandler(RefreshCookies refreshCookies) {
        this.refreshCookies = refreshCookies;
    }

    @ExceptionHandler(InvalidRequestException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRequest(InvalidRequestException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.BAD_REQUEST, "The request is not valid.");
        problem.setTitle("Invalid request");
        problem.setProperty("errors", ex.errors());
        enrich(problem);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    public ResponseEntity<ProblemDetail> handleEmailAlreadyRegistered(EmailAlreadyRegisteredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "This email is already registered.");
        problem.setTitle("Email already registered");
        enrich(problem, "EMAIL_ALREADY_REGISTERED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    public ResponseEntity<ProblemDetail> handleInvalidCredentials(InvalidCredentialsException ex) {
        // The same answer for a wrong password and for an unknown email (ADR-0004, ADR-0009).
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "The email or the password is not correct.");
        problem.setTitle("Invalid credentials");
        enrich(problem, "INVALID_CREDENTIALS");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(TooManyAttemptsException.class)
    public ResponseEntity<ProblemDetail> handleTooManyAttempts(TooManyAttemptsException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.TOO_MANY_REQUESTS, "Too many attempts. Try again later.");
        problem.setTitle("Too many requests");
        enrich(problem, "RATE_LIMITED");
        return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                .header(HttpHeaders.RETRY_AFTER, String.valueOf(ex.retryAfterSeconds()))
                .body(problem);
    }

    @ExceptionHandler(InvalidRefreshTokenException.class)
    public ResponseEntity<ProblemDetail> handleInvalidRefreshToken(InvalidRefreshTokenException ex) {
        // One answer for missing, unknown, expired, revoked and reused (ADR-0004). The dead cookie is cleared.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "The session is not valid. Log in again.");
        problem.setTitle("Invalid refresh token");
        enrich(problem, "INVALID_REFRESH_TOKEN");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                .header(HttpHeaders.SET_COOKIE, refreshCookies.clear())
                .body(problem);
    }

    @ExceptionHandler(RequestOriginNotAllowedException.class)
    public ResponseEntity<ProblemDetail> handleOriginNotAllowed(RequestOriginNotAllowedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "This request is not allowed from here.");
        problem.setTitle("Request origin not allowed");
        enrich(problem, "REQUEST_ORIGIN_NOT_ALLOWED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(TokenUserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleTokenUserNotFound(TokenUserNotFoundException ex) {
        // A valid token for a user that no longer exists: the same plain answer as any other bad credential.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.UNAUTHORIZED, "Authentication is required.");
        problem.setTitle("Unauthenticated");
        enrich(problem, "UNAUTHENTICATED");
        return ResponseEntity.status(HttpStatus.UNAUTHORIZED).body(problem);
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<ProblemDetail> handleUserNotFound(UserNotFoundException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, "No such user.");
        problem.setTitle("User not found");
        enrich(problem, "USER_NOT_FOUND");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(LocationConsentRequiredException.class)
    public ResponseEntity<ProblemDetail> handleLocationConsentRequired(LocationConsentRequiredException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.FORBIDDEN, "Location tracking is not switched on for this account.");
        problem.setTitle("Location consent required");
        enrich(problem, "LOCATION_CONSENT_REQUIRED");
        return ResponseEntity.status(HttpStatus.FORBIDDEN).body(problem);
    }

    @ExceptionHandler(ConsentTextOutdatedException.class)
    public ResponseEntity<ProblemDetail> handleConsentTextOutdated(ConsentTextOutdatedException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The consent text has changed. Show the current text and ask again.");
        problem.setTitle("Consent text outdated");
        enrich(problem, "CONSENT_TEXT_OUTDATED");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(LocationNotAvailableException.class)
    public ResponseEntity<ProblemDetail> handleLocationNotAvailable(LocationNotAvailableException ex) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.NOT_FOUND, "No location data is available for this user.");
        problem.setTitle("Location not available");
        enrich(problem, "LOCATION_NOT_AVAILABLE");
        return ResponseEntity.status(HttpStatus.NOT_FOUND).body(problem);
    }

    @ExceptionHandler(ObjectOptimisticLockingFailureException.class)
    public ResponseEntity<ProblemDetail> handleConflict(ObjectOptimisticLockingFailureException ex) {
        // Two changes to the same user at the same moment: the second one is told to try again.
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.CONFLICT, "The user was changed by someone else. Try again.");
        problem.setTitle("Conflict");
        enrich(problem, "CONFLICT");
        return ResponseEntity.status(HttpStatus.CONFLICT).body(problem);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<ProblemDetail> handleUnexpected(Exception ex) {
        log.error("unhandled exception", ex);
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(
                HttpStatus.INTERNAL_SERVER_ERROR, "An unexpected error occurred.");
        problem.setTitle("Internal server error");
        enrich(problem);
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(problem);
    }

    @Override
    protected ResponseEntity<Object> handleExceptionInternal(Exception ex, Object body,
            HttpHeaders headers, HttpStatusCode statusCode, WebRequest request) {
        ResponseEntity<Object> response =
                super.handleExceptionInternal(ex, body, headers, statusCode, request);
        if (response != null && response.getBody() instanceof ProblemDetail problem) {
            enrich(problem);
        }
        return response;
    }

    private static void enrich(ProblemDetail problem) {
        enrich(problem, codeFor(problem.getStatus()));
    }

    private static void enrich(ProblemDetail problem, String code) {
        problem.setType(URI.create(PROBLEM_BASE_URI + code.toLowerCase(Locale.ROOT).replace('_', '-')));
        problem.setProperty("code", code);
        String correlationId = MDC.get(CorrelationIdFilter.MDC_KEY);
        if (correlationId != null) {
            problem.setProperty("correlationId", correlationId);
        }
    }

    private static String codeFor(int status) {
        return switch (status) {
            case 400 -> "INVALID_REQUEST";
            case 404 -> "NOT_FOUND";
            case 409 -> "CONFLICT";
            case 429 -> "RATE_LIMITED";
            case 405 -> "METHOD_NOT_ALLOWED";
            case 406 -> "NOT_ACCEPTABLE";
            case 415 -> "UNSUPPORTED_MEDIA_TYPE";
            default -> status >= 500 ? "INTERNAL_ERROR" : "REQUEST_FAILED";
        };
    }
}
