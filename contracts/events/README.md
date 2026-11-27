# Event messages

Every event is one JSON message, described by a JSON Schema file in this folder (`<event-name>.v<major>.schema.json`). The envelope is defined in ADR-0009.

## How a message travels (ADR-0003, ADR-0009)

| Item | Rule |
|---|---|
| Exchange | `ticketflow.events`, a durable **topic** exchange. Producers declare it; the name is fixed. |
| Routing key | `<producer>.<event-name>` in lowercase with hyphens, for example `catalog.layout-published`. Derived from `eventType` (`LayoutPublished`). |
| Message ID | AMQP `messageId` = envelope `eventId` = the outbox row ID. A consumer's duplicate check uses it. |
| Delivery | Persistent, `application/json`, UTF-8. At least once: a consumer must tolerate the same message twice. |
| Queues | Owned by the **consumer**. A consumer declares its own durable queue, binds it to the routing keys it needs, and also owns its dead-letter queue. Producers never declare consumer queues. |
| No queue yet | If no queue is bound to the routing key, the broker returns the message. The producer keeps it in its outbox and retries, so a consumer that starts later still receives it. |

| Event | Producer | Routing key | Schema | Consumer queue |
|---|---|---|---|---|
| LayoutPublished | Catalog | `catalog.layout-published` | [layout-published.v1.schema.json](layout-published.v1.schema.json) | `booking.catalog.layout-published` (Booking), with `.retry` and `.dlq` next to it |
| BookingExpired | Booking | `booking.booking-expired` | [booking-expired.v1.schema.json](booking-expired.v1.schema.json) | none yet; Notification will own `notification.booking.booking-expired` (Phase 4c) |
| PaymentSucceeded | Payment | `payment.payment-succeeded` | [payment-succeeded.v1.schema.json](payment-succeeded.v1.schema.json) | none yet; Booking will own `booking.payment.payment-succeeded` (Phase 4c, PR 21) |
| PaymentFailed | Payment | `payment.payment-failed` | [payment-failed.v1.schema.json](payment-failed.v1.schema.json) | none yet; Booking will own `booking.payment.payment-failed` (Phase 4c, PR 21) |
| RefundRequested | Booking | `booking.refund-requested` | [refund-requested.v1.schema.json](refund-requested.v1.schema.json) | `payment.booking.refund-requested` (Payment), with `.retry` and `.dlq` next to it. Booking starts to send it in Phase 4c (PR 24 and 25) |
| RefundSucceeded | Payment | `payment.refund-succeeded` | [refund-succeeded.v1.schema.json](refund-succeeded.v1.schema.json) | none yet; Booking will own `booking.payment.refund-succeeded` (PR 21) and Notification `notification.payment.refund-succeeded` (PR 26) |

A consumer names its queue `<consumer>.<routing key>`, and its retry and dead-letter queues by adding `.retry` and `.dlq`.

Until a consumer's queue exists, a message waits in its producer's outbox (see "No queue yet" above). That is the case for `BookingExpired` until Notification is built: the rows stay in Booking's outbox and are published when the queue appears.
