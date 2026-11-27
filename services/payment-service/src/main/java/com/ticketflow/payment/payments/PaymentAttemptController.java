package com.ticketflow.payment.payments;

import com.ticketflow.payment.security.ServiceTokenFilter;
import com.ticketflow.payment.web.ApiException;
import com.ticketflow.payment.web.FieldProblem;
import com.ticketflow.payment.web.InvalidRequestException;
import jakarta.servlet.http.HttpServletRequest;
import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * The payment attempt calls of Booking (ADR-0002, ADR-0006; contracts/payment.internal.openapi.yaml). They are internal:
 * the gateway does not route them, and the service token filter has already refused a request without a valid service
 * token. Each call also needs its own scope. Answers are never cached.
 */
@RestController
@RequestMapping("/internal/v1/payment-attempts")
class PaymentAttemptController {

    static final String CREATE_SCOPE = "payment.attempt.create";
    static final String READ_SCOPE = "payment.attempt.read";

    /** The idempotency key of a payment attempt: the booking and the attempt number (ADR-0009, part C). */
    private static final Pattern KEY = Pattern.compile("([0-9a-fA-F-]{36}):([0-9]{1,2})");
    private static final long MAX_AMOUNT_MINOR = 1_000_000_000_000L;
    private static final Pattern CURRENCY = Pattern.compile("[A-Z]{3}");
    private static final Pattern CARD = Pattern.compile("[0-9]{12,19}");

    private final PaymentAttemptService attempts;

    PaymentAttemptController(PaymentAttemptService attempts) {
        this.attempts = attempts;
    }

    /**
     * Creates the attempt, or answers with the one that exists for the same key. The answer is 202 both times: the
     * payment is not decided yet when this call returns, its result arrives later as an event (ADR-0002).
     */
    @PostMapping
    ResponseEntity<PaymentResponse> create(HttpServletRequest request,
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateAttemptRequest body) {
        ServiceTokenFilter.requireScope(request, CREATE_SCOPE);
        Key key = parseKey(idempotencyKey);
        validate(body, key);
        Payment payment = attempts.create(key.bookingId(), key.attemptNumber(), body.amountMinor(), body.currency(),
                body.testCard());
        return ResponseEntity.status(HttpStatus.ACCEPTED)
                .location(URI.create("/internal/v1/payment-attempts/" + payment.paymentId()))
                .cacheControl(CacheControl.noStore())
                .body(PaymentResponse.of(payment));
    }

    @GetMapping("/{paymentId}")
    ResponseEntity<PaymentResponse> get(HttpServletRequest request, @PathVariable UUID paymentId) {
        ServiceTokenFilter.requireScope(request, READ_SCOPE);
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(PaymentResponse.of(attempts.get(paymentId)));
    }

    private record Key(UUID bookingId, int attemptNumber) {
    }

    private static Key parseKey(String header) {
        if (header == null || header.isBlank()) {
            throw ApiException.idempotencyKeyRequired();
        }
        Matcher matcher = KEY.matcher(header.trim());
        if (!matcher.matches()) {
            throw ApiException.idempotencyKeyInvalid();
        }
        try {
            int attempt = Integer.parseInt(matcher.group(2));
            if (attempt < 1) {
                throw ApiException.idempotencyKeyInvalid();
            }
            return new Key(UUID.fromString(matcher.group(1)), attempt);
        } catch (IllegalArgumentException e) {
            throw ApiException.idempotencyKeyInvalid();
        }
    }

    private static void validate(CreateAttemptRequest body, Key key) {
        List<FieldProblem> problems = new ArrayList<>();
        if (body.bookingId() == null) {
            problems.add(new FieldProblem("bookingId", "is required"));
        } else if (!body.bookingId().equals(key.bookingId())) {
            problems.add(new FieldProblem("bookingId", "must be the booking in the Idempotency-Key"));
        }
        if (body.amountMinor() == null) {
            problems.add(new FieldProblem("amountMinor", "is required"));
        } else if (body.amountMinor() < 1 || body.amountMinor() > MAX_AMOUNT_MINOR) {
            problems.add(new FieldProblem("amountMinor", "must be between 1 and " + MAX_AMOUNT_MINOR));
        }
        if (body.currency() == null || !CURRENCY.matcher(body.currency()).matches()) {
            problems.add(new FieldProblem("currency", "must be a three-letter upper case currency code"));
        }
        if (body.testCard() != null && !CARD.matcher(body.testCard()).matches()) {
            // The value is not repeated in the answer: it is a card number.
            problems.add(new FieldProblem("testCard", "must be 12 to 19 digits"));
        }
        if (!problems.isEmpty()) {
            throw new InvalidRequestException(problems);
        }
    }
}
