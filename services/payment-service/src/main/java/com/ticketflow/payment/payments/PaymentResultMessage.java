package com.ticketflow.payment.payments;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * The "payment succeeded", "payment failed" and "refund succeeded" events (ADR-0006; envelope from ADR-0009, part F; schemas in
 * contracts/events/payment-succeeded.v1.schema.json, payment-failed.v1.schema.json and
 * refund-succeeded.v1.schema.json). They carry identifiers, the
 * amount and the currency, and for a failure a short reason code: no card data, no personal data.
 *
 * <p>The JSON is written by hand because every value is a UUID, a number, a timestamp or a reason code produced here,
 * never text from a caller, so nothing needs escaping. The reason code is checked to be so.
 */
final class PaymentResultMessage {

    static final String SUCCEEDED = "PaymentSucceeded";
    static final String FAILED = "PaymentFailed";
    static final String REFUNDED = "RefundSucceeded";
    static final int EVENT_VERSION = 1;
    static final String PRODUCER = "payment-service";

    private static final Pattern REASON = Pattern.compile("[a-z_]{1,64}");

    private PaymentResultMessage() {
    }

    /**
     * @param messageId the outbox row ID, sent as the envelope's {@code eventId}
     * @param payment   the payment after it was settled; its update time is when the event happened
     */
    static String succeeded(UUID messageId, Payment payment) {
        return envelope(messageId, SUCCEEDED, payment, "");
    }

    static String failed(UUID messageId, Payment payment) {
        String reason = payment.failureReason();
        if (reason == null || !REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("A failed payment needs a reason code of lower case letters and underscores");
        }
        return envelope(messageId, FAILED, payment, ",\"reason\":\"" + reason + "\"");
    }

    /** The refund is done: the payment is REFUNDED. The reason is the one Booking gave when it asked for the refund. */
    static String refunded(UUID messageId, Payment payment) {
        String reason = payment.refundReason();
        if (reason == null || !REASON.matcher(reason).matches()) {
            throw new IllegalArgumentException("A refunded payment needs a reason code of lower case letters and underscores");
        }
        return envelope(messageId, REFUNDED, payment, ",\"reason\":\"" + reason + "\"");
    }

    private static String envelope(UUID messageId, String eventType, Payment payment, String extraData) {
        // The processor is not a request, so the event has no correlation ID.
        return "{\"eventId\":\"" + messageId + "\""
                + ",\"eventType\":\"" + eventType + "\""
                + ",\"eventVersion\":" + EVENT_VERSION
                + ",\"occurredAt\":\"" + seconds(payment.updatedAt()) + "\""
                + ",\"producer\":\"" + PRODUCER + "\""
                + ",\"correlationId\":null"
                + ",\"aggregateId\":\"" + payment.paymentId() + "\""
                + ",\"data\":{\"paymentId\":\"" + payment.paymentId() + "\""
                + ",\"bookingId\":\"" + payment.bookingId() + "\""
                + ",\"attemptNumber\":" + payment.attemptNumber()
                + ",\"amountMinor\":" + payment.amountMinor()
                + ",\"currency\":\"" + payment.currency() + "\""
                + extraData + "}}";
    }

    private static String seconds(Instant time) {
        return DateTimeFormatter.ISO_INSTANT.format(time.truncatedTo(ChronoUnit.SECONDS));
    }
}
