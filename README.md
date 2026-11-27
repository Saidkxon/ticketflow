# TicketFlow

An event ticketing platform where organizers publish events and customers pick **specific seats** on an interactive seat map, hold them, pay and receive confirmation.

Built as independently deployable **microservices** (Java, Spring Boot), each owning its own database, with a responsive **web app** on top. This repository is run like a workplace project: decisions are recorded as ADRs, every change goes through a pull request and architect review, and the system is containerized and deployed by pipeline.

## Status

| | |
|---|---|
| Charter | **v1.0 approved** (2026-11-01) |
| Current phase | **Phase 4b: Booking** (Phases 0-3 and 4a Identity and Catalog are done) |
| Code | Gateway, Catalog and Identity services, web app (sign-in, event list), contracts, Docker Compose deployment |

## Documentation

| Document | Purpose |
|---|---|
| [Project charter](docs/00-project-charter.md) | Vision, scope, requirements, risks, phase plan, decisions log |
| [System context and containers](docs/architecture/01-system-context-and-containers.md) | C4 diagrams, component responsibilities, preliminary booking flow |
| [Architecture decision records](docs/adr/README.md) | Index of ADRs and their status |
| [Phase 4b plan](docs/plans/phase-4b-booking.md) | The 15 pull requests that build Booking and the seat map |

## Repository layout

Decided in [ADR-001](docs/adr/0001-monorepo-layout.md):

```
ticketflow/
  docs/          charter, architecture, ADRs
  services/      one folder per microservice (own build, own Dockerfile, own DB)
  web/           React + TypeScript single-page app
  contracts/     OpenAPI specs and event schemas (the only shared artifacts)
  deploy/        docker compose now, production target later
  .github/       CI workflows, PR template, CODEOWNERS
```

`services/` holds the gateway, Catalog, Identity, Booking and Payment (attempts and a simulated provider) today; Notification arrives in Phase 4c.

## First-time repository setup (instructor action)

Run from the folder that contains this README:

```bash
git init -b main
git add .
git commit -m "docs: add project charter, architecture baseline and first ADRs"
```

Then create an empty repository on GitHub and push:

```bash
git remote add origin https://github.com/<your-username>/ticketflow.git
git push -u origin main
```

Branch protection, PR template and the full workflow are set up in Phase 2. For now, this single baseline commit is the only one allowed directly on `main`.
