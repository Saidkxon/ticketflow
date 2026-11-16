# Phase 4b plan: Booking

| | |
|---|---|
| **Status** | Approved by the instructor, 2026-11-16 |
| **Goal** | A customer can browse events, pick specific seats on an interactive map and hold them; an organizer can create, lay out and publish an event |
| **Out of scope** | Payment, confirmation, cancel after confirmation and notifications (Phase 4c, ADR-0006) |
| **Estimate** | 15 pull requests, about 4-5 weeks (my estimate; re-checked when Part 1 is done) |

## Why Part 1 exists

Catalog today has a minimal `events` table (title, start time, published flag) and one read-only list endpoint. It has no venue layout, seats, price tiers or organizer endpoints, although the charter listed organizer screens under Phase 4a. Booking has nothing to sell until Catalog can describe seats (FR-3), so the work starts there.

## Part 1: Catalog becomes sellable

| # | Pull request | Delivers |
|---|---|---|
| 1 | Organizer events | Create and edit a draft event as an `ORGANIZER`, with venue name and city added. Catalog checks access tokens for the first time. The gateway routes and protects the new paths. |
| 2 | Venue layout | Sections, rows, seats and price tiers for a draft event, created by a generator (for example "section A, 10 rows of 20 seats, tier Standard"). Seat IDs are opaque UUIDs (ADR-0012). |
| 3 | Publish | Publishing freezes the layout and writes a "layout published" outbox entry in the same transaction. Adds the public layout endpoint (cacheable) and the snapshot endpoint for Booking (ADR-0012, option A3). |
| 4 | Event search | Filter the public list by name, date and city (FR-4). |
| 5 | Web: organizer screens | Create an event, define the layout, publish, list my events. |

## Part 2: Booking service

| # | Pull request | Delivers |
|---|---|---|
| 6 | RabbitMQ | Broker in Compose, Catalog's outbox relay publishing to it, event schema in `contracts/`. Broker memory is measured and recorded in ADR-0003 (rule 9). |
| 7 | Booking skeleton | New service: Flyway, token check, health, CI, gateway route. |
| 8 | Provisioning | On "layout published", Booking fetches the snapshot and creates one row per seat. Idempotent; the event is "not on sale yet" until done. |
| 9 | Availability | The list of unavailable seat IDs with `ETag` and 304, a 1-2 second in-process cache, and a public gateway route (ADR-0011). |
| 10 | Holds | Ordered row locks, all or nothing, 10-minute deadline from the database clock, idempotency key, at most 8 seats, one active hold per user per event. Includes the concurrency test: 50 users on one seat, exactly one wins (NFR-1, NFR-4). |
| 11 | Expiry | Lazy expiry plus the sweeper (`SKIP LOCKED`), and `BookingExpired` through Booking's own outbox. |
| 12 | Booking reads | `GET /bookings/{id}`, release a hold, my booking list. |

## Part 3: Web

| # | Pull request | Delivers |
|---|---|---|
| 13 | Event page and seat map | Interactive and keyboard accessible, price and running total, polling behind a `SeatAvailabilitySource` interface (ADR-0011). Smoothness measured with about 2,000 seats (NFR-10). |
| 14 | Hold flow | Select, hold, countdown from the server time, graceful expiry and "seat taken" handling (FR-15). |
| 15 | Wrap-up | Smoke test extended, ADR clarifications, load check of the hold endpoint. |

## Decisions taken with this plan

- The venue layout belongs to the event; there is no reusable venue entity in the MVP.
- An Admin gives a user the `ORGANIZER` role through the existing roles endpoint; there is no self-service sign-up as organizer.
- The seat map is drawn as SVG, which is accessible and expected to be enough for about 2,000 seats; PR 13 measures it.
- No Redis. Holds stay in PostgreSQL (ADR-0012, option B1), availability uses an in-process cache, and rate limits stay in memory (ADR-0013). Revisit only if measurements show a problem, and then with an ADR first.

## Rules for every PR in this phase

- One concern per PR; ADR or contract changes come before the code that depends on them.
- Java changes are verified on the instructor's laptop (`./mvnw verify`, smoke test) and in CI before merge.
- The architect reviews the pushed branch against what was sent before the instructor merges.
