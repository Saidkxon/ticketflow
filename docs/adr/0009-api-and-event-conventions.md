# ADR-0009: API and event conventions

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-08 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

Six accepted or proposed ADRs defer details to this one: **ADR-0002** (correlation ID and idempotency key on calls and events), **ADR-0003** (event envelope), **ADR-0004** (error format for 401 and 403), **ADR-0005** and **ADR-0006** (idempotency keys for payment and booking calls), **ADR-0012** (idempotency key on holds, and the error format for seat conflicts). The web app, the gateway and five services must also agree on versioning, pagination and how contracts are written and checked. Without one convention, every service invents its own, and the browser has to special-case each of them.

This ADR decides: **(A)** how APIs are versioned, **(B)** the error format, **(C)** idempotency, **(D)** pagination and general REST rules, **(E)** how contracts are specified and checked, and **(F)** the event envelope.

## Decision drivers

1. One predictable convention across all services, readable by the web app without special cases
2. Safe retries: a repeated request never creates a second hold, payment or cancellation (FR-11, ADR-0012, ADR-0006)
3. Every failure is traceable end to end (NFR-6)
4. Contracts reviewed before code, and breaking changes caught automatically
5. Prefer standards over homemade formats

## Part A: versioning

- **Option A1: version in the URL path** (`/api/v1/...`). Visible, easy to route and test.
- **Option A2: version in a header.** Cleaner URLs, but invisible in logs and links, and easy to forget.
- **Option A3: no versioning.** Simplest until the first breaking change, then painful.

## Part B: error format

- **Option B1: a homemade JSON error shape.** Flexible but every service drifts.
- **Option B2: RFC 9457 "Problem Details for HTTP APIs"** (`application/problem+json`; it replaced RFC 7807). A standard with built-in support in Spring (`ProblemDetail`), and room for our own fields.

## Part C: idempotency

- **Option C1: rely on naturally idempotent operations only.** Does not cover "create a hold" or "start a payment".
- **Option C2: an `Idempotency-Key` header on state-changing requests, with the first response stored and replayed.** Covers every create-style operation; costs a small table per service.

## Part D: pagination

- **Option D1: offset pagination** (`page` and `size`). Simple, enough for a catalog of this size; slow and unstable for very deep or fast-changing lists.
- **Option D2: cursor pagination.** Stable for large feeds; more complex for the client.

## Part E: contracts

- **Option E1: spec-first.** The OpenAPI file is written and reviewed first; code and the web client follow it.
- **Option E2: code-first.** Generate the spec from annotations; easy to start, but the contract is only a by-product and review happens after the code exists.

## Decision

### Versioning (A): Option A1

- Public paths look like `/api/v1/events`. Internal endpoints use `/internal/v1/...` (ADR-0002, ADR-0004) and are not routed by the gateway.
- **Only breaking changes create a new major version.** Additive changes (a new optional field, a new endpoint) do not. Breaking means removing or renaming anything, changing a type or meaning, or making an optional input required.
- An old version stays available until the web app no longer uses it. The framework's own versioning support is checked against the current Spring documentation when the first service is scaffolded.

### Errors (B): Option B2

Every non-2xx response from every service has this shape (`Content-Type: application/problem+json`):

```json
{
  "type": "https://api.ticketflow.example/problems/seat-unavailable",
  "title": "Some seats are no longer available",
  "status": 409,
  "code": "SEAT_UNAVAILABLE",
  "detail": "2 of the 3 requested seats were taken.",
  "instance": "/api/v1/events/4f8e.../holds",
  "correlationId": "9b2c0a52-...",
  "unavailableSeatIds": ["c1a7...", "e90d..."]
}
```

- `code` is a **stable, machine-readable** identifier. The web app switches on `code`, never on `title` or `detail` text. Codes are documented in the OpenAPI file.
- `unavailableSeatIds` is the extra field ADR-0012 requires for hold conflicts. Validation failures carry an `errors` list (field and problem).
- **Standard status codes:** 400 invalid input, 401 not authenticated, 403 not allowed, 404 not found, 409 conflict (such as seats taken), 422 an idempotency key reused with a different request, 429 too many requests (with `Retry-After`), 500 and 503 for server faults.
- **401 and 403 are deliberately plain** (ADR-0004): no hint whether an account exists, which part of a token was wrong, or which role would have worked. Details go to the logs, not to the caller. A resource the caller must not know exists returns 404, not 403.
- **Never expose stack traces, SQL, class names or internal hostnames** in any response.
- `title` and `detail` are in **English** in the MVP; the web app translates by `code` if the product later needs other languages.

### Idempotency (C): Option C2

- **Required** on these state-changing requests: create hold, start payment, confirm or cancel a booking, and any other create-style `POST`. The header is `Idempotency-Key`, a UUID chosen by the caller.
- **Booking to Payment** uses the key `bookingId:attemptNumber` (ADR-0006), carried in the same header.
- A service stores, in its **own database** (ADR-0005), the key, the caller's user ID, a fingerprint of the request, and the response (status and body). The key is unique per user.
- **Behavior:**
  - Same key, same request: the **original response is replayed**, with the same status code. Nothing is executed twice.
  - Same key, different request: **422**, code `IDEMPOTENCY_KEY_REUSED`.
  - Same key while the first request is still running: **409**, code `REQUEST_IN_PROGRESS`; the client retries shortly.
- Stored keys are kept for **24 hours** by default, then removed by a cleanup job.
- A request that is missing a required key gets **400**, code `IDEMPOTENCY_KEY_REQUIRED`.
- Retries by clients and by the browser's own retry logic **must reuse the same key**. A new user action (the customer clicks "pay" again deliberately) generates a new key.

### Correlation ID (NFR-6)

- Header **`X-Correlation-ID`**, a UUID. The gateway creates one if the request has none, and **replaces** a supplied value that is not a valid UUID, so clients cannot inject arbitrary text into logs.
- It is passed on every synchronous call, written into every event (see F), attached to every log line, and returned in every response and every error body.
- If ADR-0008 adds distributed tracing (the W3C `traceparent` header), the correlation ID stays as the human-friendly handle.

### Pagination and general REST rules (D, plus common rules): Option D1

- List endpoints take `page` (starting at 0) and `size` (default **20**, maximum **50**) and return `{ "items": [...], "page": 0, "size": 20, "totalItems": 137 }`. Sorting uses an explicit, documented `sort` parameter with a stable tie-breaker so pages do not shuffle.
- Filters are query parameters, for example `city`, `from`, `to` and `q` on the event list (FR-4).
- Paths use **plural nouns in kebab-case**; JSON fields use **camelCase**. Identifiers are UUID strings. Timestamps are **ISO 8601 in UTC** with a trailing `Z`. **Money is an integer in the smallest unit plus a currency code:** `{ "amountMinor": 150000, "currency": "UZS" }`; never a decimal number (ADR-0005).
- Status codes on success: 200 for reads and updates, **201 with a `Location` header** for creates, **202 Accepted** when work continues asynchronously (for example, starting a payment, ADR-0002), 204 for empty successes.
- **Request bodies are bound to dedicated request objects, never directly to database entities**, so a client cannot set fields it should not (mass assignment). Input is validated at the edge, and request size is limited.
- Cache rules are declared per endpoint in the contract (for example, the unavailable-seat list may be cached for one or two seconds, ADR-0012).
- When the first editable resource exists (organizer edits an event), updates use `ETag` and `If-Match` with the version column from ADR-0005; a mismatch returns 412.
- The web app handles `429` and `503` with back-off, and never retries a state-changing request without its original idempotency key.

### Contracts (E): Option E1

- Every service has an **OpenAPI 3.1 file in `contracts/`** (ADR-0001, rule R2). **The file is the source of truth**, and a change to behavior starts with a change to the file in a pull request.
- CI runs a **breaking-change check** comparing the file with `main`, and **contract tests** that verify real responses conform to the spec. A breaking change without a new major version fails the build.
- The web app's typed API client is **generated** from the same files, so frontend and backend cannot silently drift.
- Event schemas live in `contracts/events/` as JSON Schema files and are checked the same way.
- Tools and versions are chosen and pinned when the first service is scaffolded.

### Event envelope (F)

Every event on RabbitMQ (ADR-0003) is a JSON message with this envelope:

```json
{
  "eventId": "0d7f...",
  "eventType": "PaymentSucceeded",
  "eventVersion": 1,
  "occurredAt": "2026-11-08T09:41:07Z",
  "producer": "payment-service",
  "correlationId": "9b2c0a52-...",
  "aggregateId": "6f2a...",
  "data": { "paymentId": "6f2a...", "bookingId": "b13e..." }
}
```

- `eventId` is unique and is what consumers record to ignore duplicates (ADR-0003, rule 2). The same value is the outbox row's ID (ADR-0005).
- `eventType` is **past tense and PascalCase** (ADR-0002). The routing key is the lowercase producer name and event name, for example `payment.payment-succeeded`.
- `data` stays **small and carries identifiers plus the minimum facts needed** (ADR-0003, rule 5). It contains **no personal data beyond identifiers**.
- **Compatibility:** within one `eventVersion`, only **additive** changes are allowed, and consumers **ignore unknown fields**. A breaking change publishes a new `eventVersion`, and the producer sends both versions until every consumer has moved.
- Each event has a JSON Schema in `contracts/events/`; the producer's tests and each consumer's tests validate against it.

## Consequences

**Positive**
- One predictable shape for errors, retries, pages and events; the web app needs no per-service special cases
- Safe retries everywhere money or seats are involved
- Contract changes are reviewed before code, and breaking changes are caught by CI
- A failed request can be followed from the browser through every service by one ID

**Negative / risks**
- **Idempotency storage and replay logic is real work** and easy to get subtly wrong. Mitigation: one carefully reviewed implementation pattern, copied into each service (ADR-0001, rule R2), with the required tests below.
- **Spec-first slows the first change a little.** Mitigation: accepted; it is the point of reviewing contracts early.
- **Offset pagination degrades on very large lists.** Mitigation: fine for the MVP's catalog; revisit with cursor pagination if lists grow.
- **Plain 401 and 403 responses make debugging harder.** Mitigation: the correlation ID leads straight to detailed server logs.
- **Maintaining two version axes** (API versions and event versions). Mitigation: strict additive-only rules and a CI check.

## Test obligations

1. The same request repeated with the same idempotency key creates one hold or payment and replays the identical response
2. The same key with a different body returns 422; a missing required key returns 400
3. Two concurrent requests with the same key execute once; the second gets 409 or the replayed response
4. Every error response is valid `application/problem+json`, includes `code` and `correlationId`, and never contains a stack trace or internal detail
5. A supplied invalid correlation ID is replaced; the ID appears in logs, responses and events
6. Real responses and real events validate against their contract files in CI
7. The breaking-change check fails when a field is removed from a published contract without a new version
8. List endpoints reject `size` above the maximum and keep a stable order across pages
9. A request body containing server-controlled fields (for example an owner ID or a role) cannot set them

## Open questions for the instructor (defaults apply if unchanged)

1. **UI languages:** English only in the MVP, with the `code`-based design leaving room to translate later (default)? Or should the web app support other languages from the start?
2. **Idempotency key retention:** 24 hours (default)?
3. **Page size:** default 20, maximum 50 (default)?

## Revisit when

- A public API for third parties is introduced (stricter versioning and deprecation policy)
- Lists grow large enough that deep offset pages are slow (move to cursor pagination)
- A real payment provider imposes its own idempotency or webhook conventions

## Related decisions

- **ADR-0002, ADR-0003:** call styles and the event transport that use these conventions
- **ADR-0004:** the plain 401 and 403 behavior
- **ADR-0005:** the idempotency table and `version` column live in each service's own database
- **ADR-0006, ADR-0012:** idempotency on payments and holds, and the seat-conflict error
- **ADR-0008:** logging and tracing, which use the correlation ID
- **ADR-0010:** the web app consumes the generated client and error codes

## References

- Charter v1.0: FR-4, FR-10, FR-11, NFR-6
- ADR-0002 (rules 4, 8), ADR-0003 (rules 2, 5, 6), ADR-0004 (Related decisions), ADR-0012 (Part B: taking a hold, idempotency key rule)
- RFC 9457, "Problem Details for HTTP APIs" (the standard that replaced RFC 7807)

## Clarifications

- **2026-11-14, idempotency and anonymous requests.** The `Idempotency-Key` rule applies to create-style requests made by a signed-in user, because the key is stored per user. Registration, login, refresh and logout are exempt: registration is anonymous and is already protected by the unique email, and the other three do not create a business record. A retry after a lost response to a registration gets 409 `EMAIL_ALREADY_REGISTERED`, which the web app treats as "log in instead".
