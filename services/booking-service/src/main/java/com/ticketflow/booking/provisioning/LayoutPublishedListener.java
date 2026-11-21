package com.ticketflow.booking.provisioning;

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
 * Receives "layout published" (ADR-0003, ADR-0012), fetches the layout from Catalog and creates the seat inventory.
 *
 * <p>The message is acknowledged only after the inventory is committed (rule 3). A failure that may pass (Catalog
 * down, database busy) rejects the message into the retry queue, which sends it back after a delay; after
 * {@code maxAttempts} tries, or at once for a message that can never work, it is copied to the dead-letter queue and
 * acknowledged, so it cannot block the queue or disappear (rule 4). Dead-letter queue depth is the alert (ADR-0008).
 */
@Component
public class LayoutPublishedListener {

    static final String EVENT_TYPE = "LayoutPublished";
    static final int EVENT_VERSION = 1;

    private static final Logger log = LoggerFactory.getLogger(LayoutPublishedListener.class);

    private final JsonMapper json = JsonMapper.builder().build();
    private final CatalogClient catalog;
    private final ProvisioningService provisioning;
    private final RabbitTemplate rabbit;
    private final ProvisioningProperties properties;

    public LayoutPublishedListener(CatalogClient catalog, ProvisioningService provisioning, RabbitTemplate rabbit,
            ProvisioningProperties properties) {
        this.catalog = catalog;
        this.provisioning = provisioning;
        this.rabbit = rabbit;
        this.properties = properties;
    }

    @RabbitListener(queues = ProvisioningConfig.QUEUE)
    public void onMessage(Message message) {
        try {
            handle(message);
        } catch (InvalidMessageException e) {
            log.error("layout published message cannot be used, parking it: {}", e.getMessage());
            park(message, e.getMessage());
        } catch (RuntimeException e) {
            int attempt = attemptOf(message.getMessageProperties());
            if (attempt >= properties.maxAttempts()) {
                log.error("layout published message failed {} times, parking it ({})", attempt,
                        e.getClass().getSimpleName());
                park(message, "failed " + attempt + " times: " + e.getClass().getSimpleName());
                return;
            }
            log.warn("layout published message failed on attempt {} of {}, will try again in {} ({}: {})", attempt,
                    properties.maxAttempts(), properties.retryDelay(), e.getClass().getSimpleName(), e.getMessage());
            // Rejected without requeue: the broker moves it to the retry queue (ProvisioningConfig).
            throw new AmqpRejectAndDontRequeueException("provisioning failed, retrying later");
        } finally {
            MDC.remove(CorrelationIdFilter.MDC_KEY);
        }
    }

    private void handle(Message message) {
        LayoutPublishedEnvelope envelope = parse(message);
        String correlationId = validCorrelationId(envelope.correlationId());
        if (correlationId != null) {
            MDC.put(CorrelationIdFilter.MDC_KEY, correlationId);
        }
        UUID eventId = envelope.data().eventId();
        long layoutVersion = envelope.data().layoutVersion();
        String body = catalog.fetchLayout(eventId, correlationId);
        CatalogLayout layout;
        try {
            layout = json.readValue(body, CatalogLayout.class);
        } catch (RuntimeException e) {
            throw new InvalidMessageException("the layout from Catalog is not valid JSON of the expected shape");
        }
        provisioning.provision(eventId, layoutVersion, layout);
    }

    private LayoutPublishedEnvelope parse(Message message) {
        LayoutPublishedEnvelope envelope;
        try {
            envelope = json.readValue(new String(message.getBody(), StandardCharsets.UTF_8),
                    LayoutPublishedEnvelope.class);
        } catch (RuntimeException e) {
            throw new InvalidMessageException("the message is not valid JSON of the expected shape");
        }
        if (envelope == null || !EVENT_TYPE.equals(envelope.eventType()) || envelope.eventVersion() == null
                || envelope.eventVersion() != EVENT_VERSION) {
            throw new InvalidMessageException("the message is not a LayoutPublished version " + EVENT_VERSION);
        }
        if (envelope.data() == null || envelope.data().eventId() == null || envelope.data().layoutVersion() == null
                || envelope.data().layoutVersion() < 1) {
            throw new InvalidMessageException("the message does not name an event and a layout version");
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
                        && ProvisioningConfig.QUEUE.equals(String.valueOf(death.get("queue")))
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
            rabbit.send("", ProvisioningConfig.DEAD_LETTER_QUEUE, new Message(original.getBody(), target), correlation);
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
