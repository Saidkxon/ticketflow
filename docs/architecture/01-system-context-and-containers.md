# System context and containers (C4 levels 1 and 2)

| | |
|---|---|
| **Status** | v2, Proposed (replaces the v1 baseline; becomes Accepted together with the ADRs) |
| **Date** | 2026-11-09 |
| **Governs** | Charter v1.1, section 8 |
| **Reflects** | ADR-0001 to ADR-0013 |

v1 of this document listed six open questions and treated every service-to-service arrow as a hypothesis. The ADRs now answer them (see the last section). This version shows the decided design. Where a decision is deferred on purpose (deployment target, backups, log retention), the text says so.

## Level 1: System context

```mermaid
flowchart LR
    customer["Customer<br/>browses events, picks seats, pays"]
    organizer["Organizer<br/>creates and publishes events"]
    admin["Admin<br/>moderates events and users"]
    tf["TicketFlow<br/>event ticketing platform"]
    pay["Payment provider<br/>(external; simulated in MVP)"]
    mail["Email delivery<br/>(local mail catcher in dev)"]

    customer -->|"uses (web)"| tf
    organizer -->|"uses (web)"| tf
    admin -->|"uses (web)"| tf
    tf -->|"requests payments"| pay
    tf -->|"sends notifications"| mail
```

Assumption still to confirm at the first Notification slice: notifications go out by email. The channel is not fixed by the charter.

## Level 2: Containers

```mermaid
flowchart TB
    browser["Browser"]

    subgraph TicketFlow
        web["Web App<br/>React + TypeScript SPA<br/>static files (ADR-0010)"]
        gw["API Gateway<br/>Spring Cloud Gateway, Server Web MVC<br/>(ADR-0013)"]

        subgraph services["Microservices (Java 25, Spring Boot 4.1, ADR-0007)"]
            idsvc["Identity"]
            catsvc["Event Catalog"]
            booksvc["Booking<br/>(saga coordinator)"]
            paysvc["Payment"]
            notifsvc["Notification"]
        end

        broker[["RabbitMQ<br/>(ADR-0003; added in Phase 4b,<br/>not in the walking skeleton)"]]

        subgraph pg["One PostgreSQL 18 instance per environment (ADR-0005)"]
            iddb[("identity DB")]
            catdb[("catalog DB")]
            bookdb[("booking DB")]
            paydb[("payment DB")]
            notifdb[("notification DB")]
        end
    end

    browser -->|"same origin: static files"| web
    browser -->|"HTTPS, /api/** (REST + JSON)"| gw
    gw -->|"validates JWT, forwards it unchanged"| idsvc
    gw --> catsvc
    gw --> booksvc

    idsvc --- iddb
    catsvc --- catdb
    booksvc --- bookdb
    paysvc --- paydb
    notifsvc --- notifdb

    gw -.->|"fetches signing keys"| idsvc
    booksvc -->|"sync: create payment attempt<br/>(idempotency key, returns at once)"| paysvc
    booksvc -->|"sync: fetch layout snapshot"| catsvc

    catsvc -.->|"LayoutPublished"| broker
    paysvc -.->|"PaymentSucceeded / PaymentFailed<br/>RefundSucceeded"| broker
    booksvc -.->|"BookingConfirmed / BookingExpired<br/>BookingCancelled / RefundRequested"| broker
    broker -.-> booksvc
    broker -.-> paysvc
    broker -.-> notifsvc
```

Solid lines are synchronous requests. Dashed lines are events through the outbox and RabbitMQ (ADR-0002, ADR-0003) or key fetching. The complete list of allowed synchronous service-to-service calls is the two lines from Booking (ADR-0002, rule 5). Any new one requires an ADR update first.

**Cross-cutting (not drawn):** every request and event carries a correlation ID (ADR-0009). Services expose health endpoints, structured logs and metrics, and send traces through OpenTelemetry (ADR-0008); the `grafana/otel-lgtm` stack is opt-in and for development only. Services never trust the gateway: each validates the token itself (ADR-0004).

## Component responsibilities and data ownership

| Component | Responsible for | Owns (source of truth) | Does **not** own |
|---|---|---|---|
| Web App | Rendering, seat selection UX, hold countdown, polling for availability and payment status (ADR-0011) | Nothing persistent (access token in memory only) | Business rules |
| API Gateway | Routing, early token check, rate limiting, correlation ID, never routes `/internal/**` | Nothing | Business logic |
| Identity | Registration, login, token issuing, refresh rotation, roles, published signing keys | Users, credentials, roles, refresh-token hashes | Anything about events or bookings |
| Event Catalog | Events, venues, seat **layout**, price tiers, publishing, immutable layout snapshots | Event and venue definitions | Whether a seat is currently taken |
| Booking | Seat **state** per event, holds, lazy expiry and sweeper, bookings, the booking and payment saga, cancellation | Seat state, holds, bookings | Event details, payment outcomes |
| Payment | Payment attempts, idempotency, simulated provider, refunds | Payment records | Bookings, seats |
| Notification | Templates, sending, delivery log | Delivery log | Triggering rules (reacts to events only; exposes no endpoints to other services) |

**Ground rules (charter, ADR-0001 to ADR-0013):**
1. One database per service; no other service reads or writes it (NFR-2). Isolation is checked by an automated test (ADR-0005).
2. Services learn about each other's data only through APIs or events, never through shared tables or shared runtime libraries (ADR-0001, rule R2).
3. The Booking service is the single authority on whether a seat is available (NFR-1). A stale map in the browser can cause a refused hold, never a double sale (ADR-0011, ADR-0012).
4. Facts are events; requests are synchronous and idempotent (ADR-0002).

## Booking flow (happy path, as decided in ADR-0006 and ADR-0012)

```mermaid
sequenceDiagram
    actor C as Customer (Web App)
    participant G as API Gateway
    participant B as Booking
    participant P as Payment
    participant N as Notification

    C->>G: Hold seats (seat IDs, idempotency key)
    G->>B: Create hold
    B->>B: One transaction: lock seat rows in order,<br/>all takeable? mark HELD until database time + 10 min
    B-->>C: 201 hold (heldUntil, serverTime)
    C->>G: Pay (idempotency key)
    G->>B: Start payment
    B->>B: One transaction: PAYMENT_PENDING,<br/>extend hold by grace period (2 min)
    B->>P: Create payment attempt (idempotency key)
    P-->>B: Payment ID (immediately)
    B-->>C: 202 processing
    loop every 2 to 5 seconds
        C->>G: GET booking status
        G->>B: status
        B-->>C: PAYMENT_PENDING
    end
    P-)B: PaymentSucceeded (via outbox and RabbitMQ)
    B->>B: Confirmation transaction: all seats still HELD by this booking?<br/>yes: seats SOLD, booking CONFIRMED, BookingConfirmed to outbox
    B-)N: BookingConfirmed
    N-->>C: Confirmation message
    C->>G: GET booking status
    B-->>C: CONFIRMED
```

The failure paths are decided in ADR-0006 and are not repeated here: payment failure (back to `HELD`, up to 3 attempts), provider timeout (unknown outcome, not failure), duplicate and late events (conditional updates and refunds), hold expiring during payment (grace period, then refund if payment arrives late), and cancellation after confirmation.

## How the earlier open questions were answered

| # | Question (v1) | Answer | ADR |
|---|---|---|---|
| Q1 | How does Booking learn the seat layout Catalog owns? | Catalog publishes an immutable layout snapshot and a `LayoutPublished` event. Booking fetches the snapshot and creates its seat rows idempotently | 0012, 0002 |
| Q2 | Where do holds live and how do they expire? | In Booking's PostgreSQL seat rows, with database-clock deadlines. Lazy expiry gives correctness, a sweeper gives tidiness | 0012 |
| Q3 | Is booking-to-payment synchronous or event-driven? | Both: a synchronous create call that returns at once, and the result as an event. Booking coordinates the saga | 0002, 0006 |
| Q4 | Do we need a message broker in the MVP? | Yes, RabbitMQ with the outbox, introduced in Phase 4b. The walking skeleton runs without it | 0003 |
| Q5 | How does the UI get live seat updates? | Polling (3 seconds, ETag and 304), behind a replaceable interface | 0011 |
| Q6 | Where is the token validated? | At the gateway early and again in every service. The original token is forwarded unchanged | 0004, 0013 |

## Still deliberately open

- **Deployment target, backups, log retention, TLS termination and the production edge component** are decided at the deployment gate before the walking skeleton is declared done (charter section 7, ADR-0005, ADR-0008, ADR-0013).
- **Email as the notification channel** is confirmed at the first Notification slice.