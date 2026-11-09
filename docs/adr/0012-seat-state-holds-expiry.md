# ADR-0012: Seat-state ownership, holds and expiry

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-01 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

With specific seats, two services touch the same real-world object. The **Event Catalog** defines the seat layout of an event (sections, rows, individually numbered seats, price tiers). The **Booking** service decides whether each seat is currently available, held or sold. This ADR decides who stores what, how Booking learns the layout, and how holds work and expire.

Requirements this decision must satisfy (charter v1.0):

- **NFR-1:** a seat is never sold twice, even under concurrent requests (hard invariant)
- **NFR-3:** a restart or outage of one service must not corrupt data in another
- **NFR-4:** hold endpoint p95 under 500 ms at 50 concurrent users; if 50 users target the same seat, exactly one succeeds
- **NFR-10:** venues of about 2,000 seats
- **FR-5 to FR-9:** live per-seat availability, time-limited holds (default 10 minutes), automatic expiry, confirmation after payment, cancellation
- Ground rule from the architecture baseline: Booking is the single authority on seat availability, and no service reads another service's database.

The decision has two parts, because they are separate problems: **(A)** how Booking obtains the layout, and **(B)** how seats are held, expired and kept consistent.

## Decision drivers

1. Correctness first: no double sales, ever (NFR-1)
2. Booking keeps working when Catalog is down (NFR-3)
3. Minimal infrastructure, because everything runs on one Windows laptop
4. Simple enough to test exhaustively and to reason about in review
5. Restart-safe: no state that exists only in memory

## Part A: how Booking learns the seat layout

### Option A1: Booking asks Catalog at runtime
Each hold request calls Catalog to validate seats and prices.
- Pros: no copy of data
- Cons: Catalog outage stops all booking (violates the spirit of NFR-3); extra latency on the hottest path; Booking is no longer the sole authority

### Option A2: Full layout carried inside the "event published" message
Catalog publishes one message containing every seat; Booking stores it.
- Pros: Booking needs nothing else from Catalog
- Cons: a 2,000-seat layout is roughly 100+ KB per message and grows with venue size; message schema and layout schema become tightly coupled; large messages are awkward for brokers

### Option A3: Small "event published" notification, then Booking fetches an immutable layout snapshot
Catalog records "event X, layout version N was published" reliably. Booking receives that small notification and fetches the snapshot for exactly that version from Catalog, then builds its own inventory.
- Pros: small messages; Catalog outage matters only while an event is being set up, never during selling; the snapshot endpoint is cacheable and idempotent
- Cons: one extra request during provisioning; Booking needs a "not yet on sale" state for an event until provisioning completes

## Part B: how seats are held and expired

### Option B1: PostgreSQL in the Booking database is the only source of truth (conditional updates)
One row per seat per event. A hold is a single atomic transaction that changes the requested seats from available to held only if every one of them is free.
- Pros: strong consistency from the database itself; no extra infrastructure; survives restarts because everything, including expiry time, is stored data
- Cons: popular events concentrate writes on a few rows; every query must handle expired holds correctly

### Option B2: Redis for holds (key with time-to-live), PostgreSQL for final sales
- Pros: fast, native expiry
- Cons: two sources of truth that can disagree after a crash; Redis key-expiry notifications are not a reliable trigger; extra service to run and secure; correctness depends on careful coordination

### Option B3: Distributed lock (for example Redlock-style) around seat purchases
- Pros: familiar idea
- Cons: locks are a poor substitute for database constraints; failure modes (clock drift, lock expiry mid-operation) are exactly the double-sale bugs we must avoid

## Decision

### Part A: Option A3 (notification, then immutable snapshot)

1. **Layout and prices are immutable once an event is published** (MVP rule). A correction means unpublishing before any sale or creating a new event. This removes an entire class of consistency problems.
2. Catalog records a **"layout published" outbox entry in the same database transaction** that publishes the event, so the notification cannot be lost. Delivery is **at-least-once**; the transport (message broker or direct internal call) is decided in ADR-0003. This ADR only requires the outbox and at-least-once semantics.
3. On notification, Booking **fetches the layout snapshot for that version** from Catalog and creates its inventory: **one row per seat per event**, state `AVAILABLE`, with the **price copied in** (so Booking never needs Catalog to compute a total). Creation is idempotent (inserting an existing seat is ignored), so duplicate notifications are harmless.
4. Until provisioning finishes, the event is **not on sale** in Booking; hold requests return a clear "not on sale yet" response.
5. Every seat has an **opaque, immutable seat ID** issued by Catalog. Section, row and number are display labels only.

### Part B: Option B1 (PostgreSQL conditional updates, dense seat rows)

**Seat states:** `AVAILABLE`, `HELD`, `SOLD`. Each seat row stores its event, state, the owning booking (when held or sold), the hold deadline (when held), and the copied price.

**Taking a hold (one database transaction, all or nothing):**
1. Lock the requested seat rows **in a fixed order (sorted by seat ID)** so two overlapping requests cannot deadlock.
2. A seat is takeable if it is `AVAILABLE`, or `HELD` with a deadline that has already passed.
3. If **every** requested seat is takeable, mark all as `HELD` with a deadline of **database time + 10 minutes**. If **any** is not, change nothing and return a conflict that names which seats were unavailable.
4. Use a **short lock timeout** (for example 1-2 seconds) so heavy contention produces a quick "try again" instead of a growing queue.

Illustrative shape of the core statement (not a final schema):

```sql
-- Runs after the ordered row locks are taken, in the same transaction.
UPDATE event_seat
   SET state = 'HELD',
       booking_id = :bookingId,
       held_until = now() + interval '10 minutes'
 WHERE event_id = :eventId
   AND seat_id  = ANY(:seatIds)
   AND (state = 'AVAILABLE' OR (state = 'HELD' AND held_until <= now()));
-- If the number of updated rows differs from the number of requested seats:
-- roll back and return a conflict.
```

**Expiry is data-driven, in two layers:**
- **Lazy expiry (correctness):** a `HELD` seat whose deadline has passed is treated as available **by every query and every hold attempt**. Correctness never depends on a background job running on time.
- **Sweeper (cleanliness):** a scheduled job periodically marks expired holds as released and emits a `BookingExpired` event (ADR-0006) for each booking whose hold ran out, so Notification can tell the customer. It processes small batches with `FOR UPDATE SKIP LOCKED`, so several instances can run safely, and it is idempotent.

**Other rules:**
- **One clock:** all deadlines and comparisons use the **database clock**, never application-server clocks, to avoid skew between instances.
- **Hold requests carry an idempotency key**, so a retried request does not create a second hold. (Header convention goes in ADR-0009.)
- **Limits against hoarding:** a maximum number of seats per hold (default **8**, configurable) and a maximum of **one active hold per user** per event in the MVP, plus gateway rate limiting.
- **Payment in flight:** when payment starts, the booking moves to a payment-pending state and the hold deadline is **extended by a short grace period**, so the sweeper and lazy expiry cannot release seats while a payment is being processed. Exact transitions, late payments after expiry and refunds belong to ADR-0006.
- **Availability reads (FR-5, FR-18):** the UI fetches the static layout from Catalog once (cacheable) and fetches from Booking only the **set of currently unavailable seat IDs**: `SOLD` plus `HELD` with a future deadline. This payload is small and can be cached for one or two seconds. Pushing updates to the browser is decided in ADR-0011.
- **Isolation level:** the default (`READ COMMITTED`) with explicit row locks is sufficient; we do not rely on stricter isolation.

## Consequences

**Positive**
- The database enforces NFR-1 directly; there is no second system to disagree with it
- Booking sells tickets even if Catalog or the broker is down (NFR-3)
- No extra infrastructure beyond PostgreSQL, which keeps the laptop workload small
- Restart-safe by construction: expiry lives in data, not in timers

**Negative / risks**
- **Hot rows:** a big on-sale moment concentrates contention on the same seats. Mitigation: short lock timeout, fast conflict responses, a load test early (NFR-4); a waiting-room or queue is a later option.
- **Every query must apply the "expired hold = available" rule.** A missed condition is a correctness bug. Mitigation: one repository method owns the rule, and it has its own tests.
- **Provisioning delay:** a just-published event is briefly not on sale. Mitigation: explicit state and a clear message in the UI.
- **Storage:** about 2,000 rows per event. Acceptable; archive old events later.
- **Immutable layouts** mean organizers cannot edit a published seat map. This is a deliberate MVP simplification.

## Test obligations (required before the Booking service is declared done)

1. 50 concurrent requests for the same seat: **exactly one** succeeds (NFR-1, NFR-4)
2. Concurrent requests for overlapping multi-seat sets: no deadlock, no partial holds
3. Service killed between taking and confirming a hold: state stays consistent after restart (NFR-3)
4. Expired hold is immediately takeable by another customer even if the sweeper has not run
5. Duplicate "layout published" notification does not duplicate inventory
6. Hold during Catalog outage still works for already-provisioned events

## Revisit when

- Load tests show the hold endpoint cannot meet NFR-4 with PostgreSQL alone
- Venues grow far beyond about 20,000 seats, or hold rates require a dedicated fast store
- Product decides organizers must edit layouts after publishing

## Related decisions

- **ADR-0003:** transport for the layout-published notification (broker vs direct call)
- **ADR-0006:** payment flow, late payment after expiry, refunds, cancellation compensation
- **ADR-0009:** idempotency-key convention and error format for conflicts
- **ADR-0011:** how seat availability reaches the browser in near real time

## References

- Charter v1.0: FR-5 to FR-9, NFR-1, NFR-3, NFR-4, NFR-10
- Architecture baseline: `docs/architecture/01-system-context-and-containers.md` (questions Q1 and Q2)
