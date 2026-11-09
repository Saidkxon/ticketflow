# ADR-0011: Keeping the browser up to date (seat map, hold countdown, payment status)

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-08 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

The charter requires that seat availability on the map refreshes without a manual reload (FR-18), that a hold shows a countdown and handles expiry gracefully (FR-15), and, through ADR-0006, that the customer sees a "payment processing" state that ends in a final result. Other ADRs defer the mechanism here: ADR-0012 ("how seat availability reaches the browser in near real time"), ADR-0006 and ADR-0002 (showing the processing state).

Facts from accepted designs that shape this decision:

- **ADR-0012:** Booking exposes the **set of currently unavailable seat IDs** (`SOLD`, plus `HELD` with a future deadline), cacheable for one or two seconds. Expiry is **data-driven**: a hold that runs out becomes available **without any write and without any event**; the sweeper only tidies up later.
- **ADR-0012:** Booking's own database is the only authority. A stale seat map can cause a refused hold, **never a double sale** (NFR-1).
- **ADR-0006:** starting a payment returns quickly (HTTP 202), and the result arrives later; the booking moves `PAYMENT_PENDING` to `CONFIRMED`, `HELD` or `EXPIRED`.
- **ADR-0004:** browsing events is public; booking data is private; the access token lives in browser memory and the refresh token in an `HttpOnly` cookie.
- **ADR-0010:** the web app uses TanStack Query; the seat map is an SVG component with about 2,000 seats (NFR-10).
- **Constraints:** one developer, a laptop with limited free memory, about 50 concurrent users as the load target (NFR-4), the API gateway chosen in ADR-0013 (Spring Cloud Gateway, Server Web MVC), and no more than one Booking instance in the MVP (but ADR-0012 is designed so several can run).

This ADR decides how three things reach the browser: **(1)** seat availability, **(2)** the hold countdown, **(3)** the booking status after payment.

## Decision drivers

1. Correctness never depends on the browser being up to date (NFR-1)
2. Simple to build, test and operate for one developer
3. Works unchanged with several Booking instances and with any gateway or proxy
4. Honest about staleness: the customer sees fresh-enough data and clear feedback when it was too stale
5. Leave room to move to push later without rewriting components

## Options considered

### Option A: Polling (the browser asks every few seconds)
- Pros: plain HTTP, works through any gateway or proxy; each request reads current data, so it needs **no fan-out between Booking instances** and **handles data-driven expiry naturally**; conditional requests (`ETag`, `304 Not Modified`) keep unchanged polls tiny; easy to test; matches the TanStack Query already chosen
- Cons: updates can be a few seconds old; wasted requests when nothing changes; load grows with viewers

### Option B: Server-Sent Events (the server pushes a stream)
- Pros: near-instant updates; one-way push fits seat changes
- Cons: needs a long-lived connection held open through the gateway (proxy buffering and timeouts must be configured); with more than one Booking instance, every instance must learn about changes made by the others (extra broadcast machinery); **expiry produces no event** under ADR-0012, so the server would need timers or would show a held seat as held until the sweeper runs; the browser's built-in client cannot send an `Authorization` header (matters for private data)

### Option C: WebSockets
- Pros: two-way, very low latency
- Cons: everything in B, plus more protocol and infrastructure work; we do not need two-way messaging

### Option D: Hybrid, push a small "something changed" hint and then fetch
- Pros: instant feel with the simple, authoritative read path of Option A
- Cons: still needs a connection and fan-out, and still needs polling as the fallback; real complexity for the MVP

## Decision

**Option A, polling, in the MVP, behind a replaceable interface. Move to Option D only if measurements justify it.**

### 1. Seat availability (public)

- The web app fetches the layout once from Catalog (cacheable, ADR-0012) and **polls Booking's unavailable-seat list** for the event shown.
- **Default interval: 3 seconds**, with about ±20% random jitter so many browsers do not fire together. Polling **pauses while the browser tab is hidden** and refreshes immediately when the tab becomes visible again.
- The endpoint supports **conditional requests**: the response carries an `ETag` derived from its content, and an unchanged list returns **304** with no body. (This is a conditional read, an addition to ADR-0009's use of `ETag` for updates.)
- Responses use compression. A full list for a 2,000-seat venue is at most about 78 KB uncompressed (2,000 UUIDs at about 39 bytes each), and typically far smaller, because most seats are free early and unchanged lists return 304. If measurements show size matters, a compact per-event seat number can be added to the layout snapshot as a backward-compatible change.
- The server keeps its result in a short in-process cache (one to two seconds, as ADR-0012 allows), so 50 pollers cost about one database query per refresh, not one each.
- The gateway allows **60 availability requests per minute per address** (ADR-0013, G3); the 3-second poll with jitter uses at most about 25. Many users behind one address (a campus network, for example) can hit this limit; if measurements show it, the limit is raised, not the poll slowed.
- Requests follow ADR-0009: on **429** the client waits for the `Retry-After` period, and on **503** it backs off before trying again.
- The availability list is **public**: it contains seat IDs only, no personal data (ADR-0004), so it needs no token and no special browser API.
- **The map is a hint, not a promise.** A hold that fails because the map was stale returns **409 `SEAT_UNAVAILABLE` with `unavailableSeatIds`** (ADR-0012, ADR-0009). The web app immediately **deselects those seats, refreshes the map and tells the customer**.
- The client code reads availability through **one interface** (for example, a `SeatAvailabilitySource`), so a push implementation can replace polling without touching the seat-map component.

### 2. Hold countdown (FR-15)

- The hold response includes `heldUntil` (UTC) **and `serverTime`** (UTC). The browser computes the offset between its clock and the server's and counts down from `heldUntil`, so a wrong phone clock cannot show a wrong countdown.
- **The countdown is display only. The server is authoritative.** At zero, the app asks the server for the booking's status, shows a clear "your hold expired" message, releases the selection and refreshes the map.
- If the customer's payment is in flight (`PAYMENT_PENDING`), the deadline is the extended one from ADR-0006, and the countdown shows "processing payment" instead of an expiry message.

### 3. Booking status after payment (private)

- After starting a payment (202), the web app polls **`GET /api/v1/bookings/{id}`** with the customer's token (ADR-0004). **Start at 2 seconds, back off to at most 5 seconds**, and stop when the booking reaches a final state (`CONFIRMED`, `EXPIRED`, `CANCELLED`) or when `HELD` returns after a failed attempt (ADR-0006, rule R3).
- The screen shows **"processing payment"** meanwhile. It never reports success until the server says `CONFIRMED`, and never reports failure on a network error; a failed poll is simply retried.
- If no final state arrives after the grace period plus a margin, the screen says the payment is still being processed and that the customer will be notified (FR-12), instead of leaving them guessing.
- Because this endpoint is private, polling works with the normal `Authorization` header. This is another reason push would be harder for private data.

### Staleness budget and performance targets (starting points, to be measured)

- Worst-case staleness of the map: **about 3 seconds of polling plus up to 2 seconds of server cache, so about 5 seconds**. This is acceptable because the server decides every hold (NFR-1).
- **Availability endpoint p95 under 200 ms at 50 concurrent pollers** (local environment).
- The seat map stays smooth while updating (NFR-10): updates change only the seats whose state changed.

## Consequences

**Positive**
- Simple, standard HTTP; nothing special in the gateway
- Works with any number of Booking instances and with data-driven expiry, with no broadcast machinery
- Easy to test and to reason about; failure of a poll is harmless
- The interface leaves a clean path to push later

**Negative / risks**
- **The map can be a few seconds old,** so customers will sometimes click a seat that was just taken. Mitigation: a clear conflict message that deselects the seats and refreshes the map immediately.
- **Polling wastes some requests,** and load grows with viewers. Mitigation: 304 responses, pausing on hidden tabs, jitter, a short server cache, and gateway rate limiting.
- **A big on-sale moment will not feel "live".** Mitigation: measured; revisit rule below.
- **Payment-status polling adds load** during busy periods. Mitigation: back-off and the 5-second cap.

## Test obligations

1. The poller sends conditional requests and handles 304 without re-rendering the map
2. Polling pauses when the tab is hidden and refreshes on return
3. 429 makes the client wait for `Retry-After`; 503 triggers back-off
4. A hold conflict deselects exactly the seats in `unavailableSeatIds` and refreshes the map
5. The countdown uses the server's time offset: it stays correct with a client clock that is five minutes wrong
6. At zero, the app asks the server; it never assumes expiry on its own
7. Payment status polling ends on each final state, retries on network errors, and never shows success before `CONFIRMED`
8. A simulated expiry with **no write** (data-driven, ADR-0012) appears on the map within the staleness budget
9. A load test with 50 concurrent pollers meets the p95 target and produces about one database query per cache refresh

## Open questions for the instructor (defaults apply if unchanged)

1. **Polling in the MVP, push only if measurements say so** (default), or push from the start?
2. **Seat map refresh every 3 seconds** (default)?

## Revisit when

- Measured staleness or request load is unacceptable on busy sales, or customers complain about stale maps
- Several Booking instances run in production and a shared change-notification mechanism (for example the broker, ADR-0003) is available at low cost
- A feature needs server-initiated messages that are not requests (for example live chat, which is out of scope)

If we move to push, **Option D** (a small "something changed" hint that triggers the usual authoritative fetch) is the planned direction, because it keeps the server's read path as the single source of truth.

## Related decisions

- **ADR-0002:** synchronous queries; the 202 pattern for payments
- **ADR-0004:** public availability, private booking data
- **ADR-0006:** booking states and the grace period
- **ADR-0009:** error codes, 429 and `Retry-After`, conditional reads
- **ADR-0010:** TanStack Query and the seat-map component
- **ADR-0012:** the unavailable-seat list, lazy expiry and the server cache
- **ADR-0013:** the gateway, its rate limit for availability polling and its single-origin rule

## References

- Charter v1.0: FR-5, FR-12, FR-15, FR-18, NFR-1, NFR-4, NFR-9, NFR-10
- ADR-0012 ("Availability reads", "Expiry is data-driven"), ADR-0006 (rules R3, R6)
