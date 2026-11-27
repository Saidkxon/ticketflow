package com.ticketflow.payment.refunds;

import com.ticketflow.payment.payments.RefundNotPossibleException;
import com.ticketflow.payment.payments.RefundService;
import com.ticketflow.payment.web.CorrelationIdFilter;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.AmqpRejectAndDontRequeueException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

/**
 * Receives "refund requested" from Booking (ADR-0006 R4 and R7) and starts the refund. The refund itself is made later
 * by the payment processor, so this consumer only records the request and is quick.
 *
 * <p>The message is acknowledged only after the request is committed (ADR-0003 rule 3). A failure that may pass (the
 * database is busy, the payment is not decided yet) rejects the message into the retry queue, which sends it back after
 * a delay; after {@code maxAttempts} tries, or at once for a message that can never work, it is copied to the
 * dead-letter queue and acknowledged, so it cannot block the queue or disappear (rule 4). Redelivery is harmless: a
 * second request for the same payment changes nothing (rule 2).
 */
@Component
public class RefundRequestedListener {

    static final String EVENT_TYPE = "RefundRequested";
    static final int EVENT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(RefundRequestedListener.class);

    private final JsonMapper json = JsonMapper.builder().build();
    private final RefundService refunds;
    private final RabbitTemplate rabbit;
    private final RefundProperties properties;

    public RefundRequestedListener(RefundService refunds, RabbitTemplate rabbit, RefundProperties properties) {
        this.refunds = refunds;
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @RabbitListener(queues = RefundsConfig.QUEUE)
    public void onMessage(Message message) {
        try {
            handle(message);
        } catch (InvalidMessageException | RefundNotPossibleException e) {
            log.error("refund requested message cannot be used, parking it: {}", e.getMessage());
            park(message, e.getMessage());
        } catch (RuntimeException e) {
            int attempt = attemptOf(message.getMessageProperties());
            if (attempt >= properties.maxAttempts()) {
                log.error("refund requested message failed {} times, parking it ({})", attempt,
                        e.getClass().getSimpleName());
                park(message, "failed " + attempt + " times: " + e.getClass().getSimpleName());
                return;
            }
            log.warn("refund requested message failed on attempt {} of {}, will try again in {} ({}: {})", attempt,
                    properties.maxAttempts(), properties.retryDelay(), e.getClass().getSimpleName(), e.getMessage());
            // Rejected without requeue: the broker moves it to the retry queue (RefundsConfig).
            throw new AmqpRejectAndDontRequeueException("refund request failed, retrying later");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handle(Message message) {
        RefundRequestedEnvelope envelope = parse(message);
        String correlationId = validCorrelationId(envelope.correlationId());
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        refunds.request(envelope.data().paymentId(), envelope.data().bookingId(), envelope.data().reason());
    }

    private RefundRequestedEnvelope parse(Message message) {
        RefundRequestedEnvelope envelope;
        try {
            envelope = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                    RefundRequestedEnvelope.class);
        } catch (RuntimeException e) {
            throw new InvalidMessageException("the message is not valid JSON of the expected shape");
        }
        if (envelope == null || !EVENT_TYPE.equals(envelope.eventType()) || envelope.eventVersion() == null
                || envelope.eventVersion() != EVENT_VERSION) {
            throw new InvalidMessageException("the message is not a RefundRequested version " + EVENT_VERSION);
        }
        if (envelope.data() == null || envelope.data().paymentId() == null || envelope.data().bookingId() == null
                || envelope.data().reason() == null) {
            throw new InvalidMessageException("the message does not name a payment, a booking and a reason");
        }
        return envelope;
    }

    private static String validCorrelationId(String value) {
        if (value == null) {
            return null;
        }
        try {
            return UUID.fromString(value).toString();
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /**
     * Which delivery this is: one more than the number of times the broker has already moved the message from the
     * main queue to the retry queue (the "x-death" header, kept by the broker).
     */
    static int attemptOf(MessageProperties properties) {
        long rejected = 0;
        if (properties.getHeaders().get("x-death") instanceof List<?> deaths) {
            for (Object entry : deaths) {
                if (entry instanceof Map<?, ?> death
                        && RefundsConfig.QUEUE.equals(String.valueOf(death.get("queue")))
                        && "rejected".equals(String.valueOf(death.get("reason")))
                        && death.get("count") instanceof Number count) {
                    rejected += count.longValue();
                }
            }
        }
        return (int) Math.min(rejected + 1, Integer.MAX_VALUE - 1);
    }

    /**
     * Copies the message to the dead-letter queue and waits for the broker to confirm it. If that fails, the message is
     * rejected into the retry queue instead, so it is not lost: it comes back and is parked on a later attempt.
     */
    private void park(Message original, String reason) {
        MessageProperties source = original.getMessageProperties();
        MessageProperties target = new MessageProperties();
        target.setContentType(source.getContentType() != null ? source.getContentType()
                : MessageProperties.CONTENT_TYPE_JSON);
        target.setContentEncoding(StandardCharsets.UTF_8.name());
        target.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        target.setMessageId(source.getMessageId());
        target.setType(source.getType());
        target.setCorrelationId(source.getCorrelationId());
        target.setHeader("failure", reason);
        CorrelationData correlation = new CorrelationData(UUID.randomUUID().toString());
        try {
            // The default exchange delivers by queue name.
            rabbit.send("", RefundsConfig.DEAD_LETTER_QUEUE, new Message(original.getBody(), target), correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck() || correlation.getReturned() != null) {
                throw new AmqpRejectAndDontRequeueException("the dead-letter queue did not accept the message");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new AmqpRejectAndDontRequeueException("interrupted while parking the message");
        } catch (ExecutionException | TimeoutException | AmqpException e) {
            throw new AmqpRejectAndDontRequeueException("the dead-letter queue is not reachable ("
                    + e.getClass().getSimpleName() + ")");
        }
    }
}
