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

## Clarifications

- **2026-11-17, the layout as built in Catalog (Phase 4b, PR 2).** An organizer describes the layout of a draft event and Catalog generates the seats. A **price tier** has a name and a price in the smallest currency unit (ADR-0005); the **currency** (UZS, USD or EUR) belongs to the event. A **section** is a block of lettered rows (A, B, C, up to 26 rows) with the same number of seats in each row (up to 100, numbered from 1) and exactly one tier; a part of a hall that costs more is simply its own section. Limits: 10 tiers, 30 sections and 5,000 seats in total (NFR-10 asks for about 2,000). Saving a layout **replaces the whole layout** in one transaction: every seat gets a new random UUID, and this is harmless until the event is published, because the IDs only become the ones Booking stores at that moment. A save counts as a change of the event: it needs `If-Match`, raises the event version by one and returns the new ETag, so a stale editor is refused with 412 and two simultaneous saves cannot both win. The owner reads the layout under `/events/mine/{id}/layout`, which also works for drafts. Publishing, the public layout read and the snapshot for Booking come in PR 3; publishing will require a non-empty layout.
- **2026-11-17, publishing as built (Phase 4b, PR 3).** `POST /api/v1/events/{eventId}/publish` (ORGANIZER, `If-Match` required) publishes the caller's draft in one transaction. A compare-and-set `UPDATE` sets `published_at` and raises the version; it also locks the event row, so no layout save can slip in. Then the event is checked for readiness (a saved layout, and a start time still in the future; otherwise 409 `EVENT_NOT_READY` and everything is rolled back). Last, the `LayoutPublished` outbox row is inserted. Publishing a published event is 409 `EVENT_ALREADY_PUBLISHED`; two simultaneous calls give one 200, one 409 and one outbox row. The message carries only `eventId` and `layoutVersion` (the event version at publishing, which stays the same because a published event cannot change). The frozen layout is served by the public `GET /api/v1/events/{eventId}/layout`: every section, row and seat with its opaque ID, an `ETag` equal to the layout version, `Cache-Control: public, max-age=3600`, and 304 for a matching `If-None-Match`. It is one document for both the browser's seat map and Booking's snapshot (option A3); Booking calls Catalog directly on the Docker network. A draft or unknown event answers 404.
- **2026-11-22, provisioning as built (Phase 4b, PR 8).** `event_inventory` (one row per event: layout version, currency, seat count) and `event_seats` (one row per seat: Catalog's seat ID, section, row, number, tier name, price in the smallest unit, state `AVAILABLE`, `HELD` or `SOLD`) live in Booking's database. The hold columns come with the holds pull request. An event is **on sale exactly when its `event_inventory` row exists**: the row and all its seats are written in one transaction, so there is no half-built event, and "not on sale yet" is simply a missing row. Booking checks the layout before writing: the event and layout version must be the ones in the message, the currency a three-letter code, every seat ID unique, every section's tier known, and `totalSeats` equal to the number of seats. A layout that fails is never partly written. Booking fetches the layout over plain HTTP from Catalog (`CATALOG_URI`), only while an event is being set up. Test obligation 5 is covered by tests of a repeated and of eight simultaneous provisioning runs, and by a duplicate message through a real broker.
- **2026-11-22, the availability rule as built (Phase 4b, PR 9).** `event_seats` gets `booking_id` and `held_until`, with a table rule that ties them to the state: AVAILABLE has neither, HELD has both, SOLD has a booking and no deadline. Nothing writes them until the holds pull request. `SeatAvailabilityRepository` owns the rule "a seat is unavailable when it is SOLD, or HELD with a deadline in the future by the database clock", written once as the SQL condition `UNAVAILABLE`; a HELD seat past its deadline counts as available whether or not the sweeper has run. Code that takes holds reuses that condition. The availability read is one query (the event row joined to its unavailable seats), so it also tells "not on sale" from "all seats free".
- **2026-11-22, holds as built (Phase 4b, PR 10).** `POST /api/v1/bookings` (any signed-in user, `Idempotency-Key` required) takes 1 to 8 seats of one event in one transaction, in this order: the idempotency key is written; one advisory lock per user and event is taken (so two simultaneous requests of one customer cannot both succeed); the seat rows are locked with `FOR UPDATE` **in order of seat ID**, whatever order the caller listed them in; seats that are not free by the rule `SeatAvailabilityRepository.UNAVAILABLE` make the whole request fail with 409 `SEATS_UNAVAILABLE` and the list `unavailableSeatIds`, and nothing is changed; otherwise the booking, its seat lines and the seat rows are written with a deadline of **database time plus 10 minutes** (`clock_timestamp()`, taken when the seats are taken, not when the transaction started). New tables: `bookings` (status HELD for now, `expires_at`), `booking_seats` (what the booking contained and each price, which stays true after the seat goes to someone else) and `idempotency_keys`. A request waits for a seat that another request is changing for at most **2 seconds** (`SET LOCAL lock_timeout`), then answers 409 `SEATS_BUSY`; a deadlock would be answered the same way, and the tests show that overlapping requests do not cause one. One active hold per user per event is 409 `ACTIVE_HOLD_EXISTS`; a hold whose deadline has passed no longer counts, whether or not the sweeper has changed its status. The answer contains `expiresAt` and `serverTime` from the same database clock, for the countdown (FR-15). Limits are settings: `ticketflow.booking.holds.max-seats`, `duration`, `lock-timeout`. Test obligations 1, 2 and 4 are covered by tests against a real PostgreSQL (50 users on one seat give exactly one booking; opposite-order overlapping requests; 30 users on random seats never share a seat; an expired hold is taken at once). Obligation 3 follows from the single transaction: nothing exists half-written after a crash.
- **2026-11-22, release and reads as built (Phase 4b, PR 12).** Releasing a hold (`POST /api/v1/bookings/{id}/release`) locks the booking row, then its seats in order of seat ID, the same order as a hold request and as the sweeper, so the three cannot deadlock. A release after the sweeper has expired the booking is 409; the sweeper after a release finds a `CANCELLED` booking and leaves it. A released booking no longer counts as an active hold, so the customer can hold again at once. The seats of a hold are freed only while they are still held by that booking. Reads show a `HELD` booking past its deadline as `EXPIRED` without waiting for the sweeper, so a customer is never told "HELD" about seats that anybody can already take.
- **2026-11-22, the sweeper as built (Phase 4b, PR 11).** `ExpirySweeper` runs every 10 seconds (`ticketflow.booking.expiry.interval`) and expires up to 100 bookings per run. For each HELD booking whose `expires_at` has passed it does, in one transaction: take the booking row with `FOR UPDATE SKIP LOCKED`, lock the booking's seats **in order of seat ID**, set the seats the booking still holds (`state = 'HELD'` and its own `booking_id`) back to AVAILABLE, set the booking to EXPIRED, and write a `BookingExpired` outbox row. **One booking per transaction, on purpose:** a hold request locks its seats in order of seat ID, and a sweeper that locked the seats of several bookings in one transaction, in the order of the bookings, could wait for a seat a hold request has while that request waits for a seat the sweeper has. A seat whose hold ran out and was taken by someone else in the meantime (lazy expiry) is not touched: it is no longer this booking's. Two sweepers at once are safe (`SKIP LOCKED`) and a second run finds nothing. A seat that a hold request has locked for more than 2 seconds ends the run early; the next run continues. The same run removes idempotency keys older than 24 hours (ADR-0009), in batches of at most 1,000. Booking statuses are HELD and EXPIRED; the others come with ADR-0006. Test obligation 4 is also covered from the sweeper's side: a hold and the sweeper on the same seats at the same moment give the new customer the seats, the old booking EXPIRED, and no deadlock.
- **2026-11-24, the hold flow in the web app (Phase 4b, PR 14).** The countdown does not trust the device's clock. The answer has `expiresAt` and `serverTime` from one database clock, and their difference is the time left. The page adds that difference to the moment the request was **sent**, read from the browser's monotonic clock (`performance.now`), which a changed system time cannot move. The answer was made after the request was sent, so the page never shows more time than the server will give. A hidden tab's timers run rarely, so the display is read from the clock, never counted. One `Idempotency-Key` belongs to one choice of seats: after a timeout or a 5xx the same choice is sent again with the same key, and the server answers with the booking it already made (ADR-0009, part C); another choice gets a new key. A refused hold removes the seats the server named from the choice, tells the person and asks for the availability again. `ACTIVE_HOLD_EXISTS` shows the customer's existing hold. After a refresh the page finds a running hold by reading the newest 50 bookings (Booking has no filter by event yet; one is on the Phase 5 list), and a booking shown as `HELD` is always one whose deadline has not passed. When the countdown reaches zero the page stops showing the hold and says so; Booking gives the seats back by itself (lazy expiry and the sweeper), so the page sends nothing.
