# ADR-0003: Message broker (RabbitMQ) and when it is introduced

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-07 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

ADR-0012 requires that when an event is published, Catalog records a "layout published" notification **in the same database transaction** (a transactional outbox) and delivers it **at least once** to Booking. The architecture baseline (questions Q3 and Q4) also shows other one-way notifications: Booking tells Notification that a booking was confirmed, failed or expired, and Payment reports payment outcomes.

This ADR decides **how those notifications travel**: with a message broker, or without one. It does **not** decide which calls between services are synchronous REST and which are asynchronous; that is ADR-0002. It does not design the payment saga; that is ADR-0006.

Constraints (charter v1.0):

- One developer, about 10-15 hours per week (section 7)
- Everything must run on one Windows laptop in Docker, so memory is limited (section 7, NFR-8)
- NFR-3: an outage or restart of one service must not corrupt data in another
- Risk register: "Scope creep" and "Local machine cannot run everything"

## Decision drivers

1. At-least-once delivery that survives restarts of both sender and receiver (NFR-3, ADR-0012)
2. A small memory and operations footprint on a laptop
3. Learning value and fit with industry practice for a Spring Boot team
4. Easy to test locally and in CI
5. Do not add infrastructure before a feature needs it

## Options considered

### Option A: No broker; the outbox relay calls the receiver's HTTP endpoint
Each service keeps its outbox table. A background job in the sender reads pending outbox rows and calls an internal HTTP endpoint on the receiver, retrying until it succeeds.
- Pros: no extra infrastructure; simplest local setup
- Cons: the sender must know every receiver's address and availability; adding a second consumer of the same event means changing the sender; retry, back-off and poison-message handling must be written by hand; teaches less about the event-driven patterns used in real systems

### Option B: RabbitMQ
A queue-based broker with acknowledgements, retries and dead-letter queues. Services publish events to exchanges; consumers have their own queues.
- Pros: lightweight compared with Kafka; well-supported by Spring; at-least-once delivery with consumer acknowledgements; dead-letter queues for messages that keep failing; senders do not need to know who consumes an event; a management UI makes behavior visible while learning
- Cons: one more container to run and secure; consumers must be idempotent (needed anyway, see ADR-0012 rule 3); message ordering is only guaranteed per queue

### Option C: Apache Kafka
A distributed log with durable, replayable event streams.
- Pros: replay of history; very high throughput; common in large companies
- Cons: heavier to run than RabbitMQ and more concepts (partitions, consumer groups, offsets); built for throughput and scale we do not have; operations overhead is disproportionate for one developer and one laptop

### Option D: Use PostgreSQL itself as the transport (other services poll the sender's outbox table)
- Pros: no new infrastructure
- Cons: directly **violates NFR-2** (no service reads another service's tables); rejected outright

## Decision

**Option B: RabbitMQ, introduced when the first feature needs it, not in the walking skeleton.**

Rules:

1. **Transactional outbox on every publisher.** An event is written to the service's own outbox table in the same transaction as the business change, then a relay publishes it to RabbitMQ and marks it sent. A failed publish is retried. This guarantees no lost events even if RabbitMQ is down.
2. **At-least-once delivery; consumers are idempotent.** Every event carries a unique event ID. A consumer records processed event IDs (or uses naturally idempotent operations, as in ADR-0012) so duplicates are harmless.
3. **Acknowledge after the work is committed**, never before.
4. **Retries with back-off, then a dead-letter queue.** A message that keeps failing is parked in a dead-letter queue for inspection instead of blocking the queue or being dropped. Dead-letter queue depth is an operational alert (ADR-0008).
5. **Events are small and carry identifiers, not full copies of data.** For example, "layout published" carries the event ID and layout version; Booking then fetches the snapshot (ADR-0012, Part A).
6. **Event schemas live in `contracts/`** (ADR-0001, rule R2) and are versioned. Changes must be backward compatible within a major version.
7. **RabbitMQ is introduced at the first vertical slice that needs an event** (expected: Catalog to Booking layout provisioning, Phase 4b). The walking skeleton in Phase 3 runs **without** a broker, to keep it small.
8. **The broker is infrastructure, not a database.** No service may use RabbitMQ as its source of truth; anything important lives in the service's own PostgreSQL.
9. **Memory budget:** the full local stack must still start with one command (NFR-8). We measure the actual memory use of the broker container in the first slice that adds it and record the number in this ADR. Docker Compose profiles let you run only the services you are working on.

## Consequences

**Positive**
- Senders are decoupled from receivers; new consumers can be added without changing publishers
- Failures become visible and recoverable (retries, dead-letter queue) instead of silent
- Realistic experience with a widely used pattern (outbox plus broker)

**Negative / risks**
- **One more container and one more failure mode.** Mitigation: introduce it late; health check and compose profile; publish-retry through the outbox means a broker outage delays events but loses none.
- **Duplicate and out-of-order messages.** Mitigation: idempotent consumers, event IDs and the rules above; tests for duplicates are required (ADR-0012 test obligation 5).
- **Debugging asynchronous flows is harder.** Mitigation: correlation ID on every event (charter NFR-6), management UI, structured logs.
- **Operational discipline:** dead-letter queues that nobody watches become silent data loss. Mitigation: an alert and a documented procedure, set up in the observability ADR.
- **Learning load** on top of microservices and the frontend. Mitigation: the broker arrives after the skeleton, with one small first use.

## Revisit when

- We need to replay historical events or feed analytics from an event history (consider Kafka)
- Measured broker memory on the development laptop makes the full stack impractical
- Event volume or ordering needs exceed what a single RabbitMQ node can reasonably provide

## Related decisions

- **ADR-0002:** which interactions stay synchronous REST and which become events
- **ADR-0006:** payment flow and compensation
- **ADR-0008:** observability and alerting for dead-letter queues
- **ADR-0009:** event and API conventions (envelope, idempotency)
- **ADR-0012:** outbox requirement and the "layout published" event

## References

- Charter v1.0: sections 7 and 9, NFR-2, NFR-3, NFR-6, NFR-8
- ADR-0012: Part A, rule 2; test obligation 5
- Architecture baseline: `docs/architecture/01-system-context-and-containers.md`, questions Q3 and Q4
- Versions of RabbitMQ and the Spring AMQP integration are **not chosen here**. They are verified against official sources and pinned when the broker is first added to the compose file.

## Clarifications

- **2026-11-17, first outbox writer (Phase 4b, PR 3).** Catalog writes the `LayoutPublished` message into `outbox_events` in the same transaction as publishing the event (rule 1). The row ID is the envelope's `eventId`, so a consumer's duplicate check and the outbox row refer to the same ID. The whole envelope is stored as `payload`, and the schema is `contracts/events/layout-published.v1.schema.json`. The relay that reads unpublished rows and sends them to the broker, and the broker itself, come in the next Catalog pull request (plan, PR 6); until then rows wait with `published_at` empty.

- **2026-11-21, broker and relay as built (Phase 4b, PR 6).** RabbitMQ `4.3.8` runs in Compose (profiles `app` and `broker`), reachable from the host on `127.0.0.1` only. Spring AMQP comes with Spring Boot. Catalog's relay reads unpublished outbox rows in a transaction (`FOR UPDATE SKIP LOCKED`), publishes each with publisher confirms and `mandatory`, and sets `published_at` only after the broker confirmed and a queue received the message. A message no queue receives yet stays in the outbox and is retried; it does not hold back the messages behind it. If the broker is down, the run stops and retries with backoff (2 to 30 seconds); Catalog's readiness does not depend on the broker, so browsing events keeps working. Messages go to the topic exchange `ticketflow.events`; **consumers own their queues and dead-letter queues** (details in `contracts/events/README.md`). **Rule 9, broker memory, measured 2026-11-21** on the development laptop (Windows, Docker Desktop, 7.6 GiB available to Docker) with the whole stack running and no consumers: RabbitMQ 134 MiB (the management page showed 144 MiB for the Erlang node); for comparison Identity 397 MiB, Catalog 299 MiB, Gateway 295 MiB, Postgres 53 MiB, web 11 MiB. The whole stack uses about 1.2 GiB, so the broker is not a problem on this machine. The measurement is repeated when Booking and the other consumers exist, because queues and connections add to it.
- **2026-11-22, the first consumer as built (Phase 4b, PR 8).** Booking consumes `catalog.layout-published` from its own queue `booking.catalog.layout-published` and acknowledges only after the seat inventory is committed (rule 3). Retries and the dead-letter queue (rule 4) use only broker features, in three queues: a failed message is rejected, the broker moves it to `booking.catalog.layout-published.retry`, where it waits 15 seconds (a time-to-live) before the broker returns it to the main queue; the delay is `ticketflow.booking.provisioning.retry-delay`. The attempt number comes from the broker's `x-death` header. After 8 attempts (about 2 minutes, so a short Catalog outage only delays the event) the message is copied to `booking.catalog.layout-published.dlq` with a `failure` header and acknowledged. A message that can never work (not JSON, wrong event version, a layout that does not match the message) is parked at once without retries. Rule 2 is met by a naturally idempotent operation (ADR-0012), not a table of processed IDs: the event row is inserted first with `ON CONFLICT DO NOTHING`, so a duplicate or concurrent copy does nothing. A message parked after a long Catalog outage is put back by a person: publish its body again to the exchange `ticketflow.events` with the routing key `catalog.layout-published`, then remove it from the dead-letter queue (steps in `deploy/README.md`); the depth alert itself comes with the observability work (ADR-0008). Booking's readiness does not include the broker, and its consumer reconnects by itself.
- **2026-11-22, Booking's own relay and the first event nobody consumes yet (Phase 4b, PR 11).** Booking now writes events to its own `outbox_events` and publishes them with its own copy of the relay (the same code as Catalog's, with the routing prefix `booking.`; no shared library, ADR-0001 rule R2). The first event is `BookingExpired` (`contracts/events/booking-expired.v1.schema.json`). Its consumer, Notification, comes in Phase 4c, so until then no queue is bound to `booking.booking-expired`: the relay finds the message unroutable, leaves it in the outbox and logs the wait once, as designed above, and every row is delivered when the queue appears. **Known limit:** one relay run looks at the oldest 50 unpublished rows; while more than 50 unroutable rows wait in front, newer rows of other event types would wait behind them. Booking has only this one event type until Phase 4c, where Notification's queue drains them; if a second event type is added first, the relay must skip rows it has just found unroutable. Cleaning old published rows out of the outbox is Phase 5.
