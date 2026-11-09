# ADR-0002: Service communication: REST for requests, events for facts

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-11-07 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

TicketFlow has a browser app, an API gateway and five services. They must talk to each other in a way that keeps each service independently deployable and keeps booking working when another service is slow or down (NFR-3). Decisions already made:

- ADR-0012: Booking is the only authority on seat availability and must not call Catalog while taking a hold. Catalog's layout reaches Booking through a "layout published" event plus an immutable snapshot fetch.
- ADR-0003: events travel through RabbitMQ using a transactional outbox, with at-least-once delivery and idempotent consumers.
- Charter: FR-10 and FR-11 (Booking requests a payment; payment requests are idempotent), FR-12 (notifications), NFR-6 (correlation ID across calls).

This ADR decides **which style each kind of interaction uses**. It does not design the payment saga (ADR-0006), authentication between services (ADR-0004) or API conventions such as error format and versioning (ADR-0009).

## Decision drivers

1. A slow or failed service must not cascade into failures of unrelated services (NFR-3)
2. Simple to understand, test and debug for one developer
3. Browser clients need plain HTTP
4. Follow decisions already accepted (ADR-0003, ADR-0012)
5. Do not introduce protocols or tooling before they are needed

## Options considered

### Option A: Everything synchronous REST
Every interaction, including notifications and payment results, is a direct HTTP call.
- Pros: simplest mental model; easy to debug with plain tools
- Cons: the sender must know and wait for every receiver; one slow service slows its callers; a failed notification call would have to fail or retry inside a booking request; contradicts ADR-0003

### Option B: Everything asynchronous (events and commands only)
Even reads and user-facing requests go through the broker.
- Pros: maximum decoupling
- Cons: the browser cannot talk to a broker; reading data becomes complicated; hard to debug; far more machinery than the MVP needs

### Option C: gRPC between services
- Pros: compact and fast; strongly typed contracts
- Cons: browsers cannot call it directly, so REST is needed at the edge anyway; adds a second contract technology and tooling; the speed gain is irrelevant at our scale

### Option D: Hybrid with clear rules: REST for requests and queries, events for facts
- Pros: each style is used where it fits; matches ADR-0003 and ADR-0012; limited number of moving parts
- Cons: developers must know which style applies; needs rules and review discipline

## Decision

**Option D: a hybrid, governed by these rules.**

1. **External API (browser to gateway to services): REST with JSON over HTTPS.** Each service publishes its OpenAPI specification in `contracts/` (ADR-0001, rule R2).
2. **Queries are synchronous REST. A service never calls another service in order to answer a read request** (for example, the seat map is built in the browser from Catalog's layout and Booking's unavailable-seat list, as in ADR-0012).
3. **Facts that have already happened are events**, named in the past tense: `LayoutPublished`, `PaymentSucceeded`, `PaymentFailed`, `BookingConfirmed`, `BookingExpired`. The full booking-related list is in ADR-0006. They use the outbox and RabbitMQ from ADR-0003.
4. **Commands that must be acted on are synchronous REST requests that return quickly.** They are **idempotent** (they carry an idempotency key) and return a status or an identifier, never a long wait.
5. **The complete list of allowed synchronous service-to-service calls in the MVP:**
   - Booking to Catalog: fetch the immutable layout snapshot (triggered by `LayoutPublished`, ADR-0012)
   - Booking to Payment: create a payment attempt; the call returns immediately with a payment ID and the result arrives later as `PaymentSucceeded` or `PaymentFailed`. (Final shape, including timeouts and late results, is decided in ADR-0006.)

   Any additional synchronous dependency requires an update to this ADR first.
6. **Everything else between services is an event.** Notification, for example, only consumes events and exposes no endpoints that other services call.
7. **Every synchronous call has a timeout** (default **2 seconds**, configurable per call) and a limited number of retries with back-off, **only for idempotent requests**. No unbounded waiting.
8. **Every call and event carries a correlation ID** (charter NFR-6). State-changing calls also carry an idempotency key; conventions and header names are fixed in ADR-0009.
9. **Internal endpoints are not exposed through the gateway.** Which service may call which, and how that is authenticated, is decided in ADR-0004.
10. **The HTTP client technology** (Spring's HTTP client support) is chosen and pinned when the first service-to-service call is implemented, after checking the current Spring Boot documentation.

## Consequences

**Positive**
- A notification or payment outage never blocks a seat hold
- The browser keeps a single simple protocol (HTTP and JSON)
- The allowed synchronous calls are few and written down, so the dependency graph is reviewable

**Negative / risks**
- Two styles to learn and to keep consistent. Mitigation: the rules above and an architecture review checklist item "is this a request or a fact?"
- Eventual consistency: after a payment, the booking status updates a moment later, and the UI must show a "processing" state. Mitigation: UI states are defined with the booking flow; real-time updates are decided in ADR-0011.
- Synchronous calls can still fail. Mitigation: timeouts, bounded retries and idempotency are mandatory, and every synchronous call must have a failure test.
- Contract drift between services. Mitigation: OpenAPI and event schemas live in `contracts/`, and changes must be backward compatible within a major version.

## Revisit when

- A service needs to call another service to answer a read request (a sign the data boundaries are wrong)
- Latency or throughput between services becomes a measured problem (consider gRPC)
- ADR-0006 changes how Booking and Payment interact

## Related decisions

- **ADR-0003:** the event transport and outbox
- **ADR-0004:** authentication, including service-to-service calls
- **ADR-0006:** payment saga and late or failed payments
- **ADR-0009:** idempotency key, correlation ID and error conventions
- **ADR-0011:** pushing seat updates to the browser
- **ADR-0012:** seat state and the layout snapshot

## References

- Charter v1.0: FR-10, FR-11, FR-12, NFR-3, NFR-6
- ADR-0003, ADR-0012
- Architecture baseline: `docs/architecture/01-system-context-and-containers.md`, question Q3