# TicketFlow: Project Charter

| | |
|---|---|
| **Status** | **APPROVED v1.0** (instructor sign-off, 2026-11-01) |
| **Phase** | 0: Discovery (closed 2026-11-01); Phase 1: Architecture in progress |
| **Owner** | Saidafzal (instructor / product owner) |
| **Architect** | Claude (Senior Architect, reviewer of all changes) |
| **Name** | TicketFlow (confirmed) |

### Change log

| Version | Change |
|---|---|
| 0.1 | Initial draft |
| 0.2 | Web UI moved **into scope**; seats modeled as **specific seats**; name confirmed; requirements, risks, ADR backlog and phase plan updated |
| 1.0 | Approved by instructor; frontend stack delegated to the architect (ADR-010); Phase 0 closed |
| 1.1 | Section 10 now points to the ADR index (the backlog grew from 12 to 13 ADRs: API gateway added as ADR-0013); phase 1 row updated; no scope change |

---

## 1. Vision

A platform where organizers publish events and customers pick specific seats on an interactive seat map, hold them, pay and receive confirmation. The backend is built as independently deployable microservices that each own their data, with a polished web frontend on top. The product is also a vehicle for learning: the goal is to practice how real engineering teams design, document, review, ship and operate distributed systems.

## 2. Goals

1. **Product:** a working end-to-end flow in the browser: register, browse events, choose seats, pay, receive confirmation.
2. **Engineering:** every change goes through a ticket, a branch, a pull request, a review and CI before merging.
3. **Architecture:** every significant decision is recorded as an ADR with alternatives and trade-offs.
4. **Operations:** the system is containerized, deployable by pipeline, and observable.
5. **Experience:** a clean, responsive, good-looking UI with a design system, not a bare admin screen.

## 3. Scope

### In scope (MVP)

- Account registration and login with role-based access (Customer, Organizer, Admin)
- Event catalog: organizers create and publish events with a venue layout of **specific seats** (sections, rows, seat numbers) and price tiers
- Booking: view live seat availability, temporarily hold specific seats, confirm, cancel
- Payment: simulated provider (success, failure, timeout, duplicate callback)
- Notifications: booking confirmed / failed / expired
- **Responsive web application** with an interactive seat map, hold countdown, booking history and an organizer dashboard
- API documentation (OpenAPI) for every service

### Non-goals (MVP)

- Real payment providers (later phase, behind the same interface)
- Native mobile apps
- Visual seat-map **editor** (venue layouts are created from a structured definition, for example JSON or a generator; the map is only **displayed** and selected in the UI)
- Multi-currency, taxes, promo codes, resale
- Multi-region or high-availability deployment

## 4. Actors

| Actor | Description |
|---|---|
| Customer | Browses events, picks seats, pays |
| Organizer | Creates and manages their own events and venue layouts |
| Admin | Moderates events and users |
| Payment provider (simulated) | External system that confirms or rejects payments |

## 5. Functional requirements (initial)

**Identity**
- FR-1: A user can register and log in; login returns a signed token.
- FR-2: Roles control access (Customer / Organizer / Admin).

**Event Catalog**
- FR-3: An organizer can create, edit and publish an event with a venue layout made of sections, rows and individually numbered seats, each assigned to a price tier.
- FR-4: Anyone can list and search published events (by date, name, city).

**Booking**
- FR-5: A customer can view live availability for each **specific seat** of an event (available / held / sold).
- FR-6: A customer can place a time-limited **hold** on one or more specific seats (default 10 minutes, maximum seats per hold set by rule).
- FR-7: An unpaid hold expires automatically and the seats are released.
- FR-8: A hold becomes a confirmed booking only after successful payment.
- FR-9: A customer can cancel a confirmed booking; seats are released.

**Payment**
- FR-10: Booking requests payment for a hold; the payment service returns success, failure or timeout.
- FR-11: Payment requests are idempotent (the same request never charges twice).

**Notification**
- FR-12: The customer is notified when a booking is confirmed, fails, or a hold expires.

**Web application**
- FR-13: Customers can browse and search events and open an event page.
- FR-14: The event page shows an interactive seat map; selected seats show price and a running total.
- FR-15: After a hold, the UI shows a countdown and handles expiry gracefully.
- FR-16: Customers see their booking history and can cancel from it.
- FR-17: Organizers have a dashboard to manage events and see sales.
- FR-18: Seat availability on the map refreshes without a manual page reload (mechanism decided in an ADR: polling vs server push).

## 6. Non-functional requirements (initial, measurable)

| ID | Requirement | How we verify |
|---|---|---|
| NFR-1 | **A seat is never sold twice**, even under concurrent requests (hard invariant) | Concurrency integration test + load test |
| NFR-2 | Each service owns its own database; no service reads another service's tables | Architecture review + automated check |
| NFR-3 | A service restart or temporary outage of one service does not corrupt data in another | Failure-injection test cases |
| NFR-4 | Seat-hold endpoint p95 latency under 500 ms at 50 concurrent users (local environment); when 50 users target the same seat, exactly one succeeds | Load test in CI or nightly |
| NFR-5 | Every service has automated unit and integration tests; CI blocks merge on failure | CI pipeline |
| NFR-6 | Every service exposes health checks, structured logs and a correlation ID across calls | Review checklist |
| NFR-7 | No secrets in the repository | Secret scanning in CI |
| NFR-8 | Whole system starts locally with one command | Documented and tested in CI |
| NFR-9 | UI is responsive (desktop and phone widths) and meets basic accessibility (keyboard navigation, contrast, labels) | Manual checklist + automated audit |
| NFR-10 | Seat map stays smooth with a venue of about 2,000 seats | Manual and performance check |

Targets are starting points and will be revised once we have real measurements.

## 7. Constraints and assumptions

- **Team:** one developer plus the architect; roughly **10-15 hours per week**. The UI competes for the same hours as the backend.
- **Stack:** Java and Spring Boot for the backend; frontend stack fixed in an ADR. Specific versions fixed after checking what is current.
- **Dev machine:** Windows laptop, so Docker memory is limited; the full stack must stay lean.
- **Payments:** simulated in MVP.
- **Deployment:** one Linux VPS running Docker Compose, decided in [ADR-0014](adr/0014-deployment-target.md) (the gate before the end of the walking skeleton). The provider is chosen separately. The system is containerized from day one.
- **Budget:** minimal; prefer free tiers and a single small server.

## 8. Preliminary components

These are hypotheses for Phase 1 and **not** final boundaries.

| Component | Owns | Database |
|---|---|---|
| Web App (SPA) | UI, client-side state | none |
| API Gateway | Routing, token validation, rate limiting | none |
| Identity | Users, credentials, roles | own PostgreSQL |
| Event Catalog | Events, venues, seat layout definition, price tiers | own PostgreSQL |
| Booking | Per-event seat state, holds, bookings | own PostgreSQL (+ cache for holds, TBD) |
| Payment | Payment attempts, idempotency keys | own PostgreSQL |
| Notification | Message templates, delivery log | own PostgreSQL |

**Open design tension (to resolve in Phase 1):** with specific seats, the Catalog owns the seat *layout* but Booking owns each seat's *state* per event. How Booking learns the layout (for example an event published by Catalog) is a core architecture decision, not a detail.

## 9. Key risks

| Risk | Impact | Mitigation |
|---|---|---|
| Scope creep (too many services too early) | Project stalls | Start with 3 services; add the rest only after the skeleton works |
| **UI doubles the workload** | Backend suffers or the project drags | Frontend built **incrementally alongside** each backend slice; UI work is capped per milestone; no custom seat-map editor |
| Local machine cannot run everything | Slow dev loop | Compose profiles; run only the services under development |
| Distributed transaction complexity (hold, pay, confirm) | Double sales, stuck holds | Saga design documented in an ADR before coding; failure tests first |
| Seat-level concurrency (many users, same seats) | Double sales or poor UX | Decide locking strategy early (ADR); concurrency tests before UI polish |
| Solo project loses momentum | Unfinished | Small weekly milestones; each milestone is a mergeable PR |
| Deployment decision postponed too long | CI/CD rework | Hard gate before the skeleton is declared done |

## 10. Architecture decisions

The decision backlog that this section used to list is now the ADR index: [docs/adr/README.md](adr/README.md). It lists every decision, its status and its file. Thirteen ADRs (0001-0013) cover Phase 1: repository layout, service communication, message broker, authentication, data and migrations, booking and payment saga, build tool and versions, observability, API and event conventions, frontend stack, real-time updates, seat state and holds, and the API gateway (added after v1.0 because other ADRs depended on it).

## 11. Phase plan (rough; ~10-15 h/week)

| Phase | Outcome | Rough duration |
|---|---|---|
| 0 | Charter signed off | this week |
| 1 | C4 diagrams, service boundaries, ADRs 0001-0013, wireframes and design direction | 2 weeks |
| 2 | Repo, branching rules, PR template, CI, code standards | ~1 week |
| 3 | Walking skeleton: gateway, one service, DB, minimal web shell, CI, container; deployment decision | 2-3 weeks |
| 4a | Identity + Catalog, with login, event browsing and organizer screens | ~4-5 weeks |
| 4b | Booking: seat model, holds, expiry, interactive seat map | ~4-5 weeks |
| 4c | Payment + Notification, completing the end-to-end flow | ~3 weeks |
| 5 | Hardening, load tests, security review, UI polish, production deployment | 2-3 weeks |

Realistic total: **about 5-6 months** at the stated pace. Estimates will be re-baselined after Phase 3.

## 12. Definition of Done (applies to every PR from Phase 2 onward)

- Linked ticket, scoped branch, small focused diff
- Tests written and passing in CI
- Documentation and OpenAPI updated if behavior changed
- ADR added or updated if a design decision was made
- Architect review approved (no merge without it)

## 13. Phase 0 exit criteria

- [x] Seat model decided: specific seats
- [x] Frontend decided: in scope, responsive web app
- [x] Name decided: TicketFlow
- [x] Instructor has reviewed and approved the charter (scope, non-goals, NFRs, timeline)
- [x] Frontend technology delegated to the architect (ADR-010)
- [ ] Charter committed as the first document in the repository (instructor action: see repo README)

## 14. Decisions log

| # | Decision | By | Date |
|---|---|---|---|
| D1 | Event ticketing platform as the project | Instructor | 2026-11-01 |
| D2 | 10-15 hours per week | Instructor | 2026-11-01 |
| D3 | Deployment target decided later, gated before end of Phase 3 | Instructor | 2026-11-01 |
| D4 | Simulated payments in MVP | Instructor | 2026-11-01 |
| D5 | Web UI in scope for MVP | Instructor | 2026-11-01 |
| D6 | Specific-seat model | Instructor | 2026-11-01 |
| D7 | Project name: TicketFlow | Instructor | 2026-11-01 |
| D8 | Charter v1.0 approved | Instructor | 2026-11-01 |
| D9 | Frontend technology decision delegated to the architect; recorded in ADR-010 | Instructor | 2026-11-01 |
| D10 | Deployment target: one VPS with Docker Compose (ADR-0014) | Instructor | 2026-11-14 |
