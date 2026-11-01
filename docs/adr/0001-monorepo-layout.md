# ADR-0001: Monorepo layout and sharing rules

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-01 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

TicketFlow consists of about five backend services, an API gateway, a web app and deployment configuration. One developer works on it with an architect reviewing every change (charter section 7). Many changes will cross boundaries, for example a new API field plus the UI that displays it. The charter requires that services remain independently deployable and own their data (NFR-2).

## Decision drivers

1. Low overhead for a solo developer (one clone, one issue tracker, one CI setup)
2. Reviewable cross-cutting changes in a single pull request
3. Services must stay decoupled even though they live together
4. Ability to split the repository later without rewriting history of ideas

## Options considered

### Option A: One monorepo for everything
- Pros: atomic cross-service changes, one place for docs and ADRs, one CI configuration, simple onboarding, easiest for the architect to review end to end
- Cons: CI needs path filtering to avoid rebuilding everything; coupling can creep in because code is "right there"

### Option B: One repository per service (polyrepo)
- Pros: hard isolation, independent permissions and pipelines, mirrors large organizations
- Cons: 7+ repositories to keep consistent for one person; cross-service changes become multiple linked PRs; duplicated CI setup; docs scattered

### Option C: Monorepo for backend, separate repository for the web app
- Pros: frontend and backend release independently
- Cons: contract changes span two repositories with no real team boundary to justify it

## Decision

**Option A: a single monorepo.**

```
ticketflow/
  docs/          charter, architecture, ADRs
  services/      identity-service, catalog-service, booking-service,
                 payment-service, notification-service, api-gateway
  web/           React + TypeScript single-page app
  contracts/     OpenAPI specs and event schemas
  deploy/        docker compose now, production target later
  .github/       workflows, PR template, CODEOWNERS
```

Sharing rules that keep it honest:

- **R1.** A service never depends on another service's code or database.
- **R2.** No shared runtime libraries between services in the MVP. Small duplicated utilities are preferred over coupling. The only shared artifacts are the specifications in `contracts/`.
- **R3.** Each service has its own build file, its own Dockerfile, its own tests and its own database, and can be built, tested and deployed alone.
- **R4.** A root parent POM may centralize dependency and plugin **versions** (build configuration only, no code).
- **R5.** CI uses path filters: a change under `services/booking-service/` builds and tests only that service. A change under `contracts/` triggers every consumer of that contract.

## Consequences

**Positive**
- Cross-cutting work fits in one PR and one review
- Documentation, diagrams and ADRs live next to the code they govern
- One CI system to learn and maintain

**Negative / risks**
- Path-filtered pipelines are more complex than one pipeline per repository. Mitigation: set up in Phase 2 and prove it in the walking skeleton.
- Temptation to share code across services. Mitigation: rules R1-R2 are checked in every review; violations block merge.
- Repository grows over time. Acceptable at this scale.

## Revisit when

- More than one team or contributor group needs separate access control or release cadence
- CI time for a single-service change becomes a regular bottleneck despite path filters

## References

- Charter v1.0: sections 7 (constraints), 8 (components), 12 (Definition of Done), NFR-2
