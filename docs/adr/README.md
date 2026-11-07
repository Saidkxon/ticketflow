# Architecture Decision Records

An ADR captures one significant decision: the context, the options we weighed, what we chose and why. We write them **before** the code that depends on them. Use [0000-template.md](0000-template.md) for new ones.

**Lifecycle:** Proposed (in a pull request) -> Accepted (merged after architect review and instructor acknowledgment) -> optionally Superseded (never edited away; a new ADR replaces it).

## Index

| ADR | Decision | Status |
|---|---|---|
| [0001](0001-monorepo-layout.md) | Monorepo layout and sharing rules | Accepted |
| 0002 | Service communication: REST vs messaging | Not started |
| 0003 | Message broker (Kafka vs RabbitMQ) or none in MVP | Not started |
| 0004 | Authentication design | Not started |
| 0005 | Data: PostgreSQL per service, migrations tool | Not started |
| 0006 | Distributed transaction style (saga) | Not started |
| [0007](0007-java-spring-build-tool.md) | Java, Spring Boot and build tool versions | Accepted |
| 0008 | Observability stack | Not started |
| 0009 | API conventions | Not started |
| [0010](0010-frontend-stack.md) | Frontend stack, design system, seat-map rendering | Accepted |
| 0011 | Real-time availability updates | Not started |
| [0012](0012-seat-state-holds-expiry.md) | Seat-state ownership, holds and expiry | Proposed |

## Suggested order for the remaining ADRs

ADRs 0002, 0003, 0006 and 0012 are tightly linked (how services talk, how a hold becomes a booking, where seat state lives). They should be decided together, starting from 0012, because the seat model drives the rest.
