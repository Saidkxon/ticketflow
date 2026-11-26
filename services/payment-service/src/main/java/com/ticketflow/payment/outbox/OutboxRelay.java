package com.ticketflow.payment.outbox;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageDeliveryMode;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Publishes the rows of the outbox to the broker (ADR-0003, rule 1). A row is marked published only after the broker has
 * confirmed that it took the message and that at least one queue received it, so an event is never lost; if the
 * database commit fails after a confirmed publish, the row is sent again (at-least-once, consumers ignore duplicates).
 *
 * <p>A message that no queue receives (nobody has bound a queue to its routing key yet) is not "published": it
 * stays in the outbox and is retried, so a consumer that starts later still gets it. It does not hold back the events
 * behind it. A broker that cannot be reached does stop the run, because every event would fail.
 */
@Component
public class OutboxRelay {

    /**
     * The result of one run: how many events were published, how many were left waiting because no queue receives them
     * yet, and why the run stopped early ({@code failure} is null when it did not, for example when the broker is down).
     */
    public record Outcome(int published, int unroutable, String failure) {
        public boolean failed() {
            return failure != null;
        }
    }

    private enum Result { PUBLISHED, UNROUTABLE, BROKER_UNAVAILABLE }

    private record Attempt(Result result, String reason) {
    }

    /** Events are routed as {@code <producer>.<event-name>}, for example {@code payment.payment-succeeded} (ADR-0009). */
    static final String ROUTING_PREFIX = "payment.";

    private final OutboxRepository repository;
    private final RabbitTemplate rabbit;
    private final TransactionTemplate transaction;
    private final OutboxProperties properties;

    OutboxRelay(OutboxRepository repository, RabbitTemplate rabbit, PlatformTransactionManager transactions,
            OutboxProperties properties) {
        this.repository = repository;
        this.rabbit = rabbit;
        // A returned (unroutable) message is read from the CorrelationData of each send. Without a callback, Spring
        // logs a warning for every returned message, once per second while no queue exists. The scheduler logs it once.
        this.rabbit.setReturnsCallback(returned -> { });
        this.transaction = new TransactionTemplate(transactions);
        this.properties = properties;
    }

    /**
     * Takes the oldest unpublished rows (rows another relay is working on are skipped) and publishes them in order. Rows
     * nobody receives yet are left for the next run; a broker failure ends the run. Rows published before that stay
     * marked. One run looks at {@code batchSize} rows, so more rows than that waiting without a queue would delay
     * the ones behind them (there is one event type today; revisit when there are several).
     */
    public Outcome runOnce() {
        return transaction.execute(status -> {
            List<PendingMessage> rows = repository.lockUnpublished(properties.batchSize());
            int published = 0;
            int unroutable = 0;
            for (PendingMessage row : rows) {
                Attempt attempt = publish(row);
                if (attempt.result() == Result.BROKER_UNAVAILABLE) {
                    return new Outcome(published, unroutable, attempt.reason());
                }
                if (attempt.result() == Result.UNROUTABLE) {
                    unroutable++;
                    continue;
                }
                repository.markPublished(row.id());
                published++;
            }
            return new Outcome(published, unroutable, null);
        });
    }

    /** Publishes one row and says whether the broker confirmed it and a queue received it. */
    private Attempt publish(PendingMessage row) {
        CorrelationData correlation = new CorrelationData(row.id().toString());
        try {
            rabbit.send(properties.exchange(), routingKey(row.eventType()), message(row), correlation);
            CorrelationData.Confirm confirm =
                    correlation.getFuture().get(properties.confirmTimeout().toMillis(), TimeUnit.MILLISECONDS);
            if (!confirm.isAck()) {
                return new Attempt(Result.BROKER_UNAVAILABLE, "the broker did not accept the message");
            }
            if (correlation.getReturned() != null) {
                return new Attempt(Result.UNROUTABLE, "no queue is bound to " + routingKey(row.eventType()));
            }
            return new Attempt(Result.PUBLISHED, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return new Attempt(Result.BROKER_UNAVAILABLE, "interrupted");
        } catch (ExecutionException | TimeoutException | AmqpException e) {
            // Only the kind of failure is reported: the message text of the exception may name internal addresses.
            return new Attempt(Result.BROKER_UNAVAILABLE,
                    "the broker is not reachable (" + e.getClass().getSimpleName() + ")");
        }
    }

    private static Message message(PendingMessage row) {
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        properties.setContentEncoding(StandardCharsets.UTF_8.name());
        properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
        properties.setMessageId(row.id().toString());
        properties.setType(row.eventType());
        if (row.correlationId() != null) {
            properties.setCorrelationId(row.correlationId());
        }
        return new Message(row.payload().getBytes(StandardCharsets.UTF_8), properties);
    }

    /** "PaymentSucceeded" becomes "payment.payment-succeeded". */
    static String routingKey(String eventType) {
        return ROUTING_PREFIX + eventType.replaceAll("([a-z0-9])([A-Z])", "$1-$2").toLowerCase(java.util.Locale.ROOT);
    }
}
