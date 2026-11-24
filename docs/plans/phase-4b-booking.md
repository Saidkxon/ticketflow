# Phase 4b plan: Booking

| | |
|---|---|
| **Status** | Approved by the instructor, 2026-11-16. Revised 2026-11-24: Parts 1, 1b and 2 are done and Part 3 is done except the close-out (PR 15) |
| **Goal** | A customer can browse events, pick specific seats on an interactive map and hold them; an organizer can create, lay out and publish an event |
| **Out of scope** | Payment, confirmation, cancel after confirmation and notifications (Phase 4c, ADR-0006) |
| **Estimate** | 17 pull requests plus 3 location pull requests (L1-L3). Done: 20 (Part 1: 6, Part 1b: 3, Part 2: 7, Part 3: 4). Left in this phase: 1 (PR 15, a small close-out; PR 13 was split in three, see below). Two checks planned for PR 15 moved to Phase 4c (see "Moved out of Phase 4b") |

## Why Part 1 exists

Catalog today has a minimal `events` table (title, start time, published flag) and one read-only list endpoint. It has no venue layout, seats, price tiers or organizer endpoints, although the charter listed organizer screens under Phase 4a. Booking has nothing to sell until Catalog can describe seats (FR-3), so the work starts there.

## Part 1: Catalog becomes sellable (done)

| # | Pull request | Delivers |
|---|---|---|
| 1 | Organizer events | Create and edit a draft event as an `ORGANIZER`, with venue name and city added. Catalog checks access tokens for the first time. The gateway routes and protects the new paths. |
| 2 | Venue layout | Sections, rows, seats and price tiers for a draft event, created by a generator (for example "section A, 10 rows of 20 seats, tier Standard"). Seat IDs are opaque UUIDs (ADR-0012). |
| 3 | Publish | Publishing freezes the layout and writes a "layout published" outbox entry in the same transaction. Adds the public layout endpoint (cacheable) and the snapshot endpoint for Booking (ADR-0012, option A3). |
| 4 | Event search | Filter the public list by name, date and city (FR-4), and by distance from the visitor ("near me"). Adds optional venue coordinates to events (ADR-0015). |
| 5a | Web: organizer events | Organizer area (role check), list my events, create and edit an event with optional venue coordinates (typed as numbers; the Google map picker comes with the map in L3). |
| 5b | Web: layout and publish | Layout editor (tiers, sections, seat count), publish with confirmation. |

## Part 1b: Location (added 2026-11-18 at the instructor's request, ADR-0015; done)

Ran after PR 5b, because it needs the web app's sign-in and settings.

| # | Pull request | Delivers |
|---|---|---|
| L1 | Identity: location data (merged) | Consent levels, position history, retention job (5 days Minimum, 30 days Full), access log, administrator read endpoints, gateway paths, contract. |
| L2 | Web: consent and sending (merged) | Consent screen after registration and login, mandatory by the instructor's decision of 2026-11-20 (the `VITE_LOCATION_ALLOW_NOT_NOW` switch, default off, brings back "Not now"), settings switch, position sender (200 m, once a minute). |
| L3 | Web: administrator map (PR #57, in review) | A map with a pin and the name of every tracked user, and the history of one user. The map is Leaflet with OpenStreetMap, not Google Maps: the instructor's Google billing account was closed (ADR-0015, note of 2026-11-21). |

The coordinates on events and the "near me" search on the server are done (PR 4). The "Near me" button and the distance on event cards in the web app come with PR 13.

## Part 2: Booking service (next)

| # | Pull request | Delivers |
|---|---|---|
| 6 | RabbitMQ (done) | Broker in Compose, Catalog's outbox relay publishing to it, event schema in `contracts/`. Broker memory is measured and recorded in ADR-0003 (rule 9). |
| 7 | Booking skeleton (done) | New service: Flyway, token check, health, CI, gateway route. |
| 8 | Provisioning (done) | On "layout published", Booking fetches the snapshot and creates one row per seat. Idempotent; the event is "not on sale yet" until done. |
| 9 | Availability (done) | The list of unavailable seat IDs with `ETag` and 304, a 1-2 second in-process cache, and a public gateway route (ADR-0011). |
| 10 | Holds (done) | Ordered row locks, all or nothing, 10-minute deadline from the database clock, idempotency key, at most 8 seats, one active hold per user per event. Includes the concurrency test: 50 users on one seat, exactly one wins (NFR-1, NFR-4). |
| 11 | Expiry (done) | Lazy expiry plus the sweeper (`SKIP LOCKED`), and `BookingExpired` through Booking's own outbox. |
| 12 | Booking reads (done) | `GET /bookings/{id}`, `GET /bookings` (my bookings, newest first, paged), `POST /bookings/{id}/release` (give up a hold: CANCELLED, seats free at once). A hold past its deadline is shown as EXPIRED before the sweeper runs. |

## Part 3: Web (after Part 2)

| # | Pull request | Delivers |
|---|---|---|
| 13a | Public event endpoint (done) | `GET /api/v1/events/{id}`: one published event for the event page, with a link that can be shared. Found while planning 13: the web app had no way to load a single event, only the list. A draft or unknown ID is 404. |
| 13b | Event page and seat map (done) | The page for one event, an SVG seat map from the frozen layout (interactive and keyboard accessible, price and running total), polling behind a `SeatAvailabilitySource` interface (ADR-0011) with a cancelled request (AbortController) when the person leaves the page. Checked with a 2,000-seat map in a real browser (NFR-10). |
| 13c | "Near me" (done) | A "Near me" button, a choice of distance (10 to 500 km, default 50) and the distance on event cards. The position is read once per search, rounded to about a kilometre, kept only in memory and never stored (ADR-0015). |
| 14 | Hold flow (done) | A "Hold seats" button (a signed-out person is sent to sign in and the choice is kept), one `Idempotency-Key` per choice of seats (a retry reuses it), a countdown from the server's `expiresAt` and `serverTime` on the device's monotonic clock, a release button, a hold that is found again after a refresh, and clear messages for `SEATS_UNAVAILABLE`, `ACTIVE_HOLD_EXISTS`, `SEATS_BUSY`, rate limits and an expired hold (FR-15). Payment does not exist yet: the page says so. |
| 15 | Close-out (in review) | Documentation only: the plan, the exit checks below, and a note in ADR-0004 about one login per browser profile. The smoke test's full hold path and the load check of the hold endpoint moved to Phase 4c, see below. |

## Phase 4b exit checks (by hand, before Phase 4c starts)

Run on a fresh stack (`docker compose -f deploy/compose.yaml --profile app up -d --build --wait`, then `bash scripts/smoke-test.sh`, all `ok`). Two different users need two different browser profiles (or a private window): the login cookie belongs to the whole profile (ADR-0004, note of 2026-11-24).

1. **Hold:** signed in, choose 2 seats of a published event and hold them. The panel shows both seats, the total and a countdown that starts near 10:00. The choice is cleared.
2. **Refresh:** reload the page. The same hold and a countdown that continues.
3. **Another user:** in a second profile, sign in as another user and open the event. The two seats are hatched ("Not available") and cannot be chosen.
4. **Release:** release the hold. The seats become free for both users within a few seconds.
5. **Expiry:** hold seats and wait for the countdown to reach 0:00. The page says the hold ended, and the seats are free for the other user.
6. **Seat taken while choosing:** the second user holds a seat that the first user has chosen but not held. The first user's choice loses that seat, with a message, and holding the rest works.
7. **Signed out:** choose a seat without signing in. The page offers sign-in, and the choice is still there after signing in.
8. **Near me:** the search finds the event with a distance, and "Show all events" brings the full list back.

## Moved out of Phase 4b

Found while planning PR 15 (2026-11-24): a smoke test of the whole hold path, and a load check of the hold endpoint, both need a published event with seats. Only an organizer can create one, and in a clean stack nobody has that role, because only an admin can grant it (ADR-0004). Both checks therefore start in Phase 4c with a test-only bootstrap of an admin and an organizer in the CI stack. The load targets stay as written in the Phase 5 row: availability p95 under 200 ms with 50 pollers, holds p95 under 500 ms with 50 users.

## What comes after Phase 4b (estimates, re-checked at the start of each phase)

| Phase | What is left to do | Rough size |
|---|---|---|
| 4c | A test-only bootstrap of an admin and an organizer in the CI stack, so the smoke test can publish an event and hold seats, plus the load check of the hold endpoint (moved from Phase 4b); Payment service with a fake provider (success, failure, timeout) and Notification service; Booking confirmation, cancel and refund flow; web checkout and "my tickets" screens; the whole path from browsing to a confirmed ticket in the smoke test (ADR-0006) | about 12-15 PRs, about 3 weeks |
| UX | A design pass over all screens: design system, layout, accessibility, phone widths. The screens are plain on purpose until now | about 1-2 weeks |
| 5 | Production Compose with resource limits, deploy job, VPS and HTTPS (browser location needs https), edge proxy with the real client address, nightly off-server backups, CI that runs only what a change touches, gateway retries, secret scanning, Maven wrapper checksum, dependency audit (npm and Maven), cleaning old refresh tokens, cleaning published outbox rows, a Booking reconciliation check that finds published events Booking has no seats for and repairs them (found on 2026-11-23: an event published before Booking existed was never provisioned), a filter on `GET /bookings` (by event and status) so the web app can find a customer's hold without reading a page of bookings, keeping the "Near me" point out of the edge proxy's access log (the log records the whole request address), load tests, a security review, location, booking and payment paths added to the gateway's protected prefixes, and the legal review of location data before real users (ADR-0015) | about 12-15 PRs, about 2-3 weeks |

## Decisions taken with this plan

- The venue layout belongs to the event; there is no reusable venue entity in the MVP.
- A section has exactly one price tier, and its rows are lettered A to Z (at most 26 rows per section); a hall with different prices is described as several sections. At most 5,000 seats per event.
- An Admin gives a user the `ORGANIZER` role through the existing roles endpoint; there is no self-service sign-up as organizer.
- The seat map is drawn as SVG, which is accessible and expected to be enough for about 2,000 seats; PR 13 measures it.
- The location question is mandatory and the administrator map uses OpenStreetMap (2026-11-20 and 2026-11-21, ADR-0015).
- No Redis. Holds stay in PostgreSQL (ADR-0012, option B1), availability uses an in-process cache, and rate limits stay in memory (ADR-0013). Revisit only if measurements show a problem, and then with an ADR first.

## Rules for every PR in this phase

- One concern per PR; ADR or contract changes come before the code that depends on them.
- Java changes are verified on the instructor's laptop (`./mvnw verify`, smoke test) and in CI before merge.
- The architect reviews the pushed branch against what was sent before the instructor merges.
