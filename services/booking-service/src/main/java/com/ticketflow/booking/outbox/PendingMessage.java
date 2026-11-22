package com.ticketflow.booking.outbox;

import java.util.UUID;

/** One outbox row that has not been published yet. {@code payload} is the whole envelope as JSON text. */
record PendingMessage(UUID id, String eventType, UUID aggregateId, String payload, String correlationId) {
}
