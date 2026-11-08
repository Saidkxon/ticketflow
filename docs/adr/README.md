# Architecture Decision Records

An ADR captures one significant decision: the context, the options we weighed, what we chose and why. We write them **before** the code that depends on them. Use [0000-template.md](0000-template.md) for new ones.

**Lifecycle:** Proposed (in a pull request) -> Accepted (merged after architect review and instructor acknowledgment) -> optionally Superseded (never edited away; a new ADR replaces it).

## Index

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-monorepo-layout.md) | Monorepo layout and sharing rules | Accepted |
| [0002](0002-service-communication.md) | Service communication: REST for requests, events for facts | Proposed |
| [0003](0003-message-broker.md) | Message broker (RabbitMQ) and when it is introduced | Proposed |
| [0004](0004-authentication.md) | Authentication and authorization | Proposed |
| [0005](0005-data-and-migrations.md) | Data storage, database topology and migrations | Proposed |
| [0006](0006-booking-payment-saga.md) | Booking and payment flow: a saga coordinated by Booking | Proposed |
| [0007](0007-java-spring-build-tool.md) | Java, Spring Boot and build tool versions | Accepted |
| 0008 | Observability stack | Not started |
| [0009](0009-api-and-event-conventions.md) | API and event conventions | Proposed |
| [0010](0010-frontend-stack.md) | Frontend stack, design system, seat-map rendering | Accepted |
| [0011](0011-realtime-seat-updates.md) | Keeping the browser up to date (seat map, hold countdown, payment status) | Proposed |
| [0012](0012-seat-state-holds-expiry.md) | Seat-state ownership, holds and expiry | Proposed |

## Suggested order for the remaining ADRs

ADRs 0002, 0003, 0006 and 0012 are tightly linked (how services talk, how a hold becomes a booking, where seat state lives). They should be decided together, starting from 0012, because the seat model drives the rest.
