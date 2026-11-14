# Contracts

The OpenAPI files here are the **source of truth** for every public API (ADR-0009, part E). A change to behavior starts with a change to the file, reviewed in a pull request before any code.

| File | Service | Status |
|---|---|---|
| [catalog.openapi.yaml](catalog.openapi.yaml) | Event Catalog | Walking skeleton: event list only |
| [identity.openapi.yaml](identity.openapi.yaml) | Identity | Proposed: register, login, refresh, logout, current user, roles, public keys (no code yet) |

Rules (ADR-0009):

- Paths are `/api/v1/...`. Only a breaking change creates a new major version; additive changes do not.
- Every error is `application/problem+json` with a stable `code` and the `correlationId`.
- Lists use `page` (from 0) and `size` (default 20, maximum 50).
- JSON fields are camelCase, IDs are UUID strings, timestamps are ISO 8601 in UTC.

Not yet in place (decided when the first endpoint ships): the automated breaking-change check, the contract tests against real responses, and the generated web client. Event schemas will live in `contracts/events/`.
