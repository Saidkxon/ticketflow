# ADR-0013: API gateway: technology, responsibilities and routing

| | |
|---|---|
| **Status** | Proposed |
| **Date** | 2026-11-08 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

The charter lists an API gateway that routes requests, validates tokens and applies rate limits, and owns no business logic (section 8). Accepted and proposed ADRs already assume it without saying what it is: ADR-0004 (the gateway rejects bad tokens early; login attempts are rate limited there; TLS ends there in production; services still validate tokens themselves), ADR-0009 (the gateway creates or replaces the correlation ID; internal endpoints are never routed), ADR-0011 (the availability endpoint is polled by many browsers), ADR-0002 (timeouts, bounded retries only for idempotent requests). ADR-0004 also left one question open: the refresh cookie and the API must be served **compatibly**. This ADR settles that too.

It decides: **(A)** what the gateway is, **(B)** what it does and does not do, **(C)** the routing table, **(D)** how the browser reaches it (one origin), and **(E)** load balancing.

## Facts checked on 2026-11-08

- **Spring Cloud 2025.1.2 ("Oakwood")**, released 2026-06-11, is compatible with Spring Boot 4.0.7 and adds compatibility with **Spring Boot 4.1.0**. It includes **Spring Cloud Gateway 5.0.2**. Source: the Spring blog post for that release. This matches the Spring Boot 4.1.x choice in ADR-0007.
- Spring Cloud Gateway comes in two flavors, **Server WebFlux** (reactive) and **Server Web MVC** (servlet-based). The project page lists version 5.0.4. Source: spring.io/spring-cloud-gateway.
- The Server Web MVC documentation lists filters for circuit breaking, rate limiting, retries, request size limits, token relay and load balancing, and has pages on TLS/SSL and Spring Security integration. Source: docs.spring.io, Spring Cloud Gateway 5.0 reference.
- **Not verified, to be confirmed when the gateway is scaffolded:** which store the Web MVC rate limiter uses (per instance or shared), whether a newer Spring Cloud release than 2025.1.2 now carries Gateway 5.0.4, and the support end dates.

## Decision drivers

1. Defense in depth is already provided by the services (ADR-0004), so the gateway must add value without becoming a trusted shortcut
2. Same language and tooling as the rest of the project; learnable by one developer
3. Small memory footprint (limited free memory on the development laptop)
4. Stays independent of the undecided deployment target (charter section 7)
5. One origin for browser and API, so the `HttpOnly` refresh cookie works as designed (ADR-0004)

## Options considered

### Option A: Spring Cloud Gateway, Server Web MVC flavor
- Pros: Java and Spring like every other service, so one stack, one build, one set of tests; the same Spring Security resource-server support as the services; filters for rate limiting, retries, request size and circuit breaking exist; straightforward to debug with ordinary stack traces
- Cons: a JVM process with its own memory cost; the Web MVC flavor is newer than the WebFlux one, so some online examples are for WebFlux; some features (for example shared rate limits) may need extra infrastructure

### Option B: Spring Cloud Gateway, Server WebFlux flavor
- Pros: non-blocking, designed for very many simultaneous connections; the longest-established flavor
- Cons: reactive programming is a second paradigm to learn for little benefit at about 50 concurrent users; harder to debug; different security and testing styles from the other services

### Option C: A reverse proxy only (Caddy, Traefik or nginx), configured with files
- Pros: very light; excellent at TLS, static files and compression; no Java code to maintain
- Cons: custom logic such as replacing an invalid correlation ID, Problem-Details error bodies and per-user rate limits needs plugins, scripts or paid features; it teaches less; ADR-0004's early token check would be weaker or hand-built

### Option D: Reverse proxy at the edge **and** Spring gateway behind it
- Pros: what many production systems do: the proxy handles TLS and static files, the gateway handles application concerns
- Cons: two components to run for the MVP; the edge part is deployment-specific and cannot be chosen before the deployment gate

### Option E: No gateway; the browser calls each service directly
- Rejected: exposes every service to the internet, multiplies cross-origin configuration, and removes the single place for rate limiting and correlation IDs

## Decision

**Option A: Spring Cloud Gateway, Server Web MVC flavor, as `services/api-gateway`.** It is the **application** gateway. The **edge** (TLS, static files, compression) is a deployment concern: the choice, if any, between a reverse proxy and the platform's ingress is made at the deployment gate and follows rule G10.

### What the gateway does and does not do

**G1. No business logic, no database, no state.** It routes, checks, limits and labels requests, and nothing else. It does **not** combine data from several services (the browser composes the seat map from Catalog and Booking, ADR-0012 and ADR-0011), does not translate between data models and has no database credentials.

**G2. Early token check, token passed through unchanged.** Protected routes require a valid access token: the gateway validates signature, expiry, issuer and audience against Identity's published keys (ADR-0004). It **forwards the original `Authorization` header unchanged**. It does **not** replace it with custom identity headers, because services must never learn to trust the gateway (ADR-0004, Part B). Public routes (listed below) need no token.

**G3. Rate limiting.** Limits are applied per client address and, where a token exists, per user. Starting points, to be tuned from measurements:

| Route group | Starting limit |
|---|---|
| Login, registration, token refresh | 10 per minute per address, stricter after repeated failures (ADR-0004) |
| Availability polling (ADR-0011) | 60 per minute per address (the 3-second poll uses about 20) |
| Everything else | 100 per minute per user or address |

In the MVP, limits are **per gateway instance** (one instance runs). A shared limit across instances needs shared storage and is deferred, together with multiple gateway instances.

**G4. Correlation ID, exactly as ADR-0009:** create one if absent, replace an invalid one, forward it, return it in the response, put it in every access-log line.

**G5. Gateway-generated errors use the ADR-0009 Problem Details format** with a stable `code` and the `correlationId`: for example `UNAUTHENTICATED` (401, deliberately plain, ADR-0004), `RATE_LIMITED` (429 with `Retry-After`), `ROUTE_NOT_FOUND` (404), `UPSTREAM_UNAVAILABLE` (503) and `UPSTREAM_TIMEOUT` (504). Error bodies never reveal internal hostnames or ports.

**G6. Timeouts and retries.** Connect and read timeouts default to **5 seconds** for user-facing calls (a starting point, above the 1-2 second lock timeout in ADR-0012). The gateway retries **only idempotent `GET` requests**, a limited number of times with back-off (ADR-0002, rule 7). It never retries a state-changing request; the client does, with its original idempotency key (ADR-0009). Circuit breakers are available in the library but **not enabled in the MVP**.

**G7. Request limits.** Maximum request body size and header size are enforced here, in addition to the services.

**G8. Observability.** One access-log line per request: method, route template (not raw IDs), status, duration, correlation ID and the user's ID (`sub`) when present, and **never** the token or any secret. Metrics and health endpoints are exposed for the platform only and are not reachable from the public side (details in ADR-0008).

**G9. Internal endpoints are never routed.** Any path starting with `/internal/` is answered with 404 at the gateway (ADR-0002, rule 9; ADR-0004; ADR-0009).

### Routing table (illustrative; the OpenAPI files in `contracts/` are authoritative)

More specific routes are matched before general ones.

| Path | Goes to | Token needed |
|---|---|---|
| `/api/v1/auth/**` (register, login, refresh, logout) | Identity | No (rate limited) |
| `/api/v1/users/**` | Identity | Yes |
| `/api/v1/events/*/holds`, `/api/v1/events/*/availability` | Booking | Holds: yes; availability: no |
| `/api/v1/events/**` (everything else under events) | Catalog | Reads: no; writes: yes |
| `/api/v1/bookings/**` | Booking | Yes |
| `/internal/**` | Nowhere (404) | n/a |

**Payment and Notification have no public routes at all in the MVP**: customers pay through Booking (ADR-0006), and Notification only consumes events (ADR-0002). Fewer public services means a smaller attack surface.

### One origin for the browser (D) and cookies

**G10. The browser talks to a single origin.** `/api/**` goes to the gateway and every other path serves the web app's static files. Consequences:

- The `HttpOnly`, `SameSite=Strict` refresh cookie from ADR-0004 works without special cross-site settings, and **no cross-origin (CORS) access is configured at all** (the safest default; it satisfies ADR-0004's "allowed only from the web app's own origin").
- **In development,** the web app's dev server forwards `/api` to the gateway, so the browser still sees one origin.
- **In production,** the component that serves the static files and forwards `/api` to the gateway is chosen at the deployment gate (a reverse proxy or the platform's ingress). The gateway itself **does not serve the web app's files**.

### Load balancing and discovery (E)

**G11. No load balancing and no service-discovery server in the MVP.** One instance of each service runs; the gateway reaches each service through a configured address (for example, the container name on the Docker network). Booking is already safe to run in several copies (ADR-0012), so adding replicas later needs **no redesign**: the platform or container network spreads traffic, and the gateway's own load-balancing filter is an option. We revisit this at the deployment gate and when multiple instances are introduced.

### Versions and setup

- The Spring Cloud release train compatible with the chosen Spring Boot 4.1.x line is added through the parent POM (ADR-0001, rule R4; ADR-0007) and pinned, never "latest".
- Whether the servlet stack runs on virtual threads (a Spring Boot setting) is decided and measured when the gateway is scaffolded.
- Rate-limit storage and the exact filter set are confirmed against the Spring Cloud Gateway 5.0 documentation at that time.

## Consequences

**Positive**
- One public entry point, with rate limits, correlation IDs and plain, consistent errors
- A gateway bypass still exposes nothing, because services check tokens (ADR-0004)
- Same stack and tests as the rest of the project; cheap to debug
- Payment and Notification are invisible from the internet
- Cookies and CORS stay simple because the browser uses one origin

**Negative / risks**
- **One more JVM** (a few hundred MB is my estimate, not a measurement) on a memory-limited laptop. Mitigation: Docker Compose profiles; measure at Phase 3; the gateway is only needed when the web app or end-to-end tests are in use.
- **The gateway is a single point of entry:** if it is down, no browser request works, although service-to-service flows and events continue. Mitigation: it is stateless and restarts quickly; multiple instances are possible later.
- **Security-sensitive code.** Mitigation: use the library's standard filters and Spring Security, avoid clever custom logic, and review every gateway PR with the security checklist (ADR-0004).
- **Per-instance rate limits** are only approximate with several instances. Accepted for the MVP.
- **The Web MVC flavor has fewer online examples** than WebFlux. Mitigation: rely on the official reference and keep the configuration small.
- **Route table mistakes** (a public path accidentally exposed) are a real risk. Mitigation: the route tests below, reviewed on every change.
- **Unverified details** listed above.

## Test obligations

1. Every public path reaches the intended service; a more specific route wins over a general one; `/internal/**` returns 404; Payment and Notification are unreachable from outside
2. Missing, expired, wrong-audience or tampered tokens get 401 on protected routes; public routes work without a token; the original `Authorization` header reaches the service unchanged
3. A missing correlation ID is created, an invalid one is replaced, and the ID appears in the response and in the access log
4. Exceeding a rate limit returns 429 in Problem Details format with `Retry-After`; the login limit is stricter than the general one
5. Upstream down returns 503 and upstream too slow returns 504, both as Problem Details without internal names
6. An idempotent `GET` is retried within the limit; a `POST` is never retried by the gateway
7. Oversized request bodies and headers are rejected
8. An architecture test confirms the gateway has no database dependency and no business logic
9. The refresh cookie works end to end through the dev proxy and the gateway (ADR-0004), without any CORS configuration
10. Access logs never contain tokens or secrets

## Open questions for the instructor (defaults apply if unchanged)

1. **Gateway technology:** Spring Cloud Gateway, Server Web MVC (default)?
2. **Rate limits:** the starting numbers in G3 and the 5-second gateway timeout, to be tuned later (default)?

## Revisit when

- Several gateway instances run and per-instance limits become inaccurate (shared limiter or edge-level limits)
- Measured memory or latency makes the gateway a problem, or the WebFlux flavor offers a decisive advantage
- Third-party API consumers appear (API keys, quotas, a developer portal)
- The deployment target already provides a gateway or ingress that makes this component redundant

## Related decisions

- **ADR-0002:** timeouts, retries, internal endpoints
- **ADR-0004:** token validation, rate limiting at the gateway, TLS, cookies and CORS
- **ADR-0007:** Spring Boot and parent POM versions
- **ADR-0008 (planned):** access logs, metrics and health endpoints
- **ADR-0009:** correlation ID and error format
- **ADR-0010, ADR-0011:** the web app and its polling of the availability endpoint
- **ADR-0012:** why Booking can scale to several instances

## References

- Charter v1.0: section 7 (deployment undecided), section 8 (gateway), NFR-6
- Spring blog: "Spring Cloud 2025.1.2 (aka Oakwood) Has Been Released", 2026-06-11: https://spring.io/blog/2026/06/11/spring-cloud-2025-1-2-aka-oakwood-has-been-released/ (checked 2026-11-08)
- Spring Cloud Gateway project page: https://spring.io/spring-cloud-gateway (checked 2026-11-08)
- Spring Cloud Gateway 5.0 reference, Server Web MVC (filter list and navigation): https://docs.spring.io/spring-cloud-gateway/reference/5.0/spring-cloud-gateway-server-webmvc/filters/setrequestheader.html (checked 2026-11-08)
