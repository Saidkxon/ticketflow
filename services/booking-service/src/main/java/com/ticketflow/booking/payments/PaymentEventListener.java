package com.ticketflow.booking.payments;

import com.ticketflow.booking.web.CorrelationIdFilter;
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
 * Receives the events of Payment (ADR-0006): {@code PaymentSucceeded}, {@code PaymentFailed} and {@code RefundSucceeded},
 * each from its own queue, and hands them to {@link PaymentEventProcessor}.
 *
 * <p>The message is acknowledged only after the handling is committed (ADR-0003 rule 3). A failure that may pass (the
 * database is busy) rejects the message into the retry queue, which sends it back after a delay; after
 * {@code maxAttempts} tries, or at once for a message that can never work, it is copied to the dead-letter queue and
 * acknowledged, so it cannot block the queue or disappear (rule 4). Dead-letter queue depth is the alert (ADR-0008).
 * A message delivered again is harmless (ADR-0006 R2).
 */
@Component
public class PaymentEventListener {

    static final int EVENT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private final JsonMapper json = JsonMapper.builder().build();
    private final PaymentEventProcessor processor;
    private final RabbitTemplate rabbit;
    private final PaymentEventsProperties properties;

    PaymentEventListener(PaymentEventProcessor processor, RabbitTemplate rabbit, PaymentEventsProperties properties) {
        this.processor = processor;
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @RabbitListener(queues = {"booking.payment.payment-succeeded", "booking.payment.payment-failed",
            "booking.payment.refund-succeeded"})
    public void onMessage(Message message) {
        String queue = message.getMessageProperties().getConsumerQueue();
        PaymentEventKind kind = PaymentEventKind.forQueue(queue).orElse(null);
        if (kind == null) {
            // Cannot happen: the listener is bound to the three queues above. Do not loop on it.
            throw new AmqpRejectAndDontRequeueException("message from an unknown queue");
        }
        try {
            handle(kind, message);
        } catch (InvalidMessageException e) {
            log.error("{} message cannot be used, parking it: {}", kind.eventType(), e.getMessage());
            park(message, kind, e.getMessage());
        } catch (RuntimeException e) {
            int attempt = attemptOf(message.getMessageProperties(), kind.queue());
            if (attempt >= properties.maxAttempts()) {
                log.error("{} message failed {} times, parking it ({})", kind.eventType(), attempt,
                        e.getClass().getSimpleName());
                park(message, kind, "failed " + attempt + " times: " + e.getClass().getSimpleName());
                return;
            }
            log.warn("{} message failed on attempt {} of {}, will try again in {} ({}: {})", kind.eventType(), attempt,
                    properties.maxAttempts(), properties.retryDelay(), e.getClass().getSimpleName(), e.getMessage());
            // Rejected without requeue: the broker moves it to the retry queue (PaymentEventsConfig).
            throw new AmqpRejectAndDontRequeueException("handling failed, retrying later");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handle(PaymentEventKind kind, Message message) {
        PaymentEventEnvelope envelope = parse(kind, message);
        String correlationId = validCorrelationId(envelope.correlationId());
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        PaymentEventEnvelope.Data data = envelope.data();
        processor.process(new PaymentEvent(envelope.eventId(), kind, data.paymentId(), data.bookingId(),
                data.attemptNumber(), data.amountMinor(), data.currency(), data.reason()));
    }

    private PaymentEventEnvelope parse(PaymentEventKind kind, Message message) {
        PaymentEventEnvelope envelope;
        try {
            envelope = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                    PaymentEventEnvelope.class);
        } catch (RuntimeException e) {
            throw new InvalidMessageException("the message is not valid JSON of the expected shape");
        }
        if (envelope == null || !kind.eventType().equals(envelope.eventType()) || envelope.eventVersion() == null
                || envelope.eventVersion() != EVENT_VERSION) {
            throw new InvalidMessageException("the message is not a " + kind.eventType() + " version " + EVENT_VERSION);
        }
        PaymentEventEnvelope.Data data = envelope.data();
        if (envelope.eventId() == null || data == null || data.paymentId() == null || data.bookingId() == null
                || data.attemptNumber() == null || data.amountMinor() == null || data.currency() == null) {
            throw new InvalidMessageException("the message does not name an event, a payment, a booking, an attempt "
                    + "and an amount");
        }
        if (kind != PaymentEventKind.PAYMENT_SUCCEEDED && data.reason() == null) {
            throw new InvalidMessageException("the message does not give a reason");
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
    static int attemptOf(MessageProperties properties, String queue) {
        long rejected = 0;
        if (properties.getHeaders().get("x-death") instanceof List<?> deaths) {
            for (Object entry : deaths) {
                if (entry instanceof Map<?, ?> death
                        && queue.equals(String.valueOf(death.get("queue")))
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
    private void park(Message original, PaymentEventKind kind, String reason) {
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
            rabbit.send("", kind.deadLetterQueue(), new Message(original.getBody(), target), correlation);
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
