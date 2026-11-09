# ADR-0008: Observability: logs, metrics, traces and health

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-08 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

Four ADRs defer observability details here: **ADR-0003** (alert on dead-letter queue depth), **ADR-0004** (log security events with the correlation ID and no secrets), **ADR-0009** (the correlation ID stays the human-friendly handle even if tracing is added) and **ADR-0013** (the gateway writes one access-log line per request and exposes metrics and health only to the platform). The charter adds **NFR-6** (health checks, structured logs, a correlation ID across calls). Two latency targets also need a way to be measured: **NFR-4** (hold endpoint p95 under 500 ms at 50 concurrent users) and **ADR-0011** (availability endpoint p95 under 200 ms at 50 pollers).

A distributed system without observability fails silently: a stuck outbox, a growing dead-letter queue or a slow hold can go unnoticed for days. At the same time, the development laptop has limited free memory (about 3.8 GB observed), so we cannot run a heavy monitoring stack all the time.

This ADR decides: **(A)** the overall approach, **(B)** how services are instrumented, **(C)** logging rules, **(D)** metrics and alert conditions, **(E)** tracing, **(F)** health checks, and **(G)** what runs where.

## Facts checked on 2026-11-08

- **Spring Boot 4 has an OpenTelemetry starter** (`spring-boot-starter-opentelemetry`), described by the Spring team as their preferred way to add OpenTelemetry. It exports **metrics** through Micrometer's OTLP registry and **traces** through Micrometer Tracing's OpenTelemetry bridge. It can send **logs** over OTLP, but Spring Boot **does not install a log appender by default**. Properties shown: `management.otlp.metrics.export.url` (default `http://localhost:4318/v1/metrics`), `management.opentelemetry.tracing.export.otlp.endpoint`, `management.opentelemetry.logging.export.otlp.endpoint`. Source: Spring blog, "OpenTelemetry with Spring Boot", 2025-11-18.
- **Spring Boot's structured logging** (introduced in 3.4) writes JSON; ECS and Logstash formats are built in, enabled with `logging.structured.format.console=ecs`, and values in the logging **MDC** (Mapped Diagnostic Context) are included automatically in the JSON. Source: Spring blog, "Structured logging in Spring Boot 3.4". Not verified for 4.1: whether names or defaults changed.
- **`grafana/otel-lgtm`** is a single container bundling an OpenTelemetry Collector, Prometheus, Tempo, Loki, Pyroscope and Grafana. Its own documentation says it is "intended for development, demo, and testing environments", not production. It gives no general memory requirement; the only hint is that a 1 GB runtime default was "insufficient for the stack" and its script sets 2 GB there. **My inference, not a measurement: plan on roughly 2 GB for the whole stack.** Source: the project's documentation page.

## Decision drivers

1. Find the cause of a failure from the correlation ID, quickly (NFR-6)
2. Measure the targets we promised (NFR-4, ADR-0011)
3. Detect silent failures: stuck outbox, dead-letter queue, expired-hold backlog
4. Stay inside the laptop's memory budget; heavy tooling must be optional
5. Do not lock services to one vendor
6. Never leak secrets or personal data through logs, metrics or traces

## Options considered

### Option A: Minimal: structured logs on standard output plus health endpoints, no metrics backend
- Pros: zero extra containers; simplest
- Cons: no way to see latency percentiles or trends; NFR-4 cannot be verified; silent failures stay silent

### Option B: A full monitoring stack always running (separate Prometheus, Loki, Tempo, Grafana containers, or an ELK stack)
- Pros: complete picture at all times
- Cons: several containers and a large memory cost on a laptop that already has little free memory; operational work out of proportion for the MVP

### Option C: Vendor-neutral OpenTelemetry (OTLP) from the services, with a lightweight default and an **opt-in** all-in-one backend for development; the production backend is chosen at the deployment gate
- Pros: services stay portable (the protocol matters, not the vendor); no cost until switched on; gives metrics and traces when we need them (load tests, debugging)
- Cons: a second mode to keep working; logs reach the backend only after extra setup (see below)

### Option D: A hosted service only, from day one
- Pros: nothing to run locally
- Cons: needs an account and internet for every development session; free-tier limits are unknown to me; sends development data off the machine

### Instrumentation (B1 vs B2)

- **B1: the Spring Boot OpenTelemetry starter plus Micrometer** (library-based). Pros: officially recommended for Boot 4, visible in the build file, no extra JVM flags.
- **B2: the OpenTelemetry Java agent** (attached to the JVM). Pros: automatic coverage. Cons: more "magic", extra startup configuration, harder to explain and test.

## Decision

**Option C, with the starter and Micrometer (B1).**

### Logging rules (C)

1. **Structured JSON on standard output** from every service in containers, using Spring Boot's built-in structured logging (ECS format as the default; the exact format is confirmed when the first service is scaffolded). Running from the IDE may use readable text. No log files inside containers.
2. **Every log line carries `correlationId`** (ADR-0009) through the MDC, and **`userId` (the token's `sub`) when the request is authenticated**. When tracing is on, trace and span IDs are included as well.
3. **Log identifiers, not personal data.** Never log passwords, tokens (access or refresh), the `Authorization` header, cookies, idempotency-key response bodies or payment details. Email addresses and names are not logged; the user ID is enough (ADR-0004, ADR-0005).
4. **Levels:** `ERROR` means a person must look at it; `WARN` means unexpected but handled; `INFO` records meaningful business events and state changes (hold created, booking confirmed, refund requested, with their IDs); `DEBUG` is off outside development.
5. **Security events** (ADR-0004) are logged at `WARN` with a fixed, stable event name and the correlation ID: failed login, refresh-token reuse detected, access denied, rate limit exceeded. Dedicated event names make them searchable and alertable.
6. **One request record per request,** written by the **gateway** (ADR-0013, G8). Services do not duplicate it; they log business events and errors.
7. **Log retention** is short and decided at the deployment gate (starting point: 14 days). Local container logs are size-limited.

### Metrics (D)

Services publish metrics through Micrometer. We get standard JVM, HTTP and database-pool metrics, including **request-duration histograms** (needed to check p95 for NFR-4 and ADR-0011). On top of those, each service adds the **business metrics** that expose silent failures:

| Service | Metrics (counters, gauges, timers) |
|---|---|
| Booking | holds created, hold conflicts (409), holds expired, bookings confirmed, bookings expired, hold-transaction duration, **age of the oldest expired-but-not-released hold (sweeper lag)**, idempotency replays |
| Payment | attempts by outcome (succeeded, failed, unknown), refunds requested and completed, provider call duration |
| Every publisher | **unpublished outbox rows and age of the oldest** (ADR-0003, ADR-0005) |
| Every consumer | events processed, duplicates ignored, retries, **dead-letter queue depth** (ADR-0003) |
| Gateway | rate-limit rejections, upstream errors and timeouts by route |
| Identity | login failures, refresh-token reuse detections |

**Cardinality rule:** a metric's labels use only small, fixed sets (route template, status class, outcome). **Never use user, booking, event or seat IDs as labels.** Those belong in logs and traces.

**Alert conditions,** documented now, wired to a notification channel when a production environment exists (starting points, tuned later):

| Condition | Why it matters |
|---|---|
| Dead-letter queue depth above zero for 5 minutes | A message keeps failing (ADR-0003) |
| Oldest unpublished outbox row older than 60 seconds | Events are not leaving a service |
| Sweeper lag above 2 minutes | Expired holds are piling up (ADR-0012) |
| Hold endpoint p95 above 500 ms, or availability p95 above 200 ms | Targets from NFR-4 and ADR-0011 are broken |
| Any refresh-token reuse detection | Possible stolen credential (ADR-0004) |
| Server-error rate above 2% for 5 minutes | General failure |
| Database pool saturated for several minutes | Connection starvation (ADR-0005) |

Until alerting exists, dashboards are checked by a person during load tests and releases.

### Tracing (E)

- Tracing is on via the starter. **Sampling: 100% in development and tests, a lower rate (starting point 10%) in production.** The gateway starts the trace and the W3C `traceparent` header travels on every synchronous call (ADR-0009). The correlation ID remains the primary, human-friendly handle.
- Trace data follows the same privacy rules as logs: no tokens or personal data in span attributes.
- **Across RabbitMQ:** events already carry the correlation ID. Adding an optional `traceparent` field to the event envelope is an **additive change** (allowed within an event version, ADR-0009) and is made when tracing across events is first needed.

### Health checks (F)

- Every service exposes **liveness** and **readiness** through Spring Boot Actuator on a **separate management port that is not routed by the gateway** (ADR-0013, G8) and is not published outside the container network except in local development.
- **Readiness depends on the service's own database only.** A failure of RabbitMQ or of another service **must not** make Booking unready: holds keep working during a broker outage (ADR-0003, ADR-0012). Such dependencies are reported as **informational** health, not as readiness failures.
- Only health (and, where needed, metrics) is exposed; no other Actuator endpoints are exposed.

### What runs where (G)

| Mode | What runs | When |
|---|---|---|
| **Default (local)** | Services only: JSON logs on standard output, health endpoints. **No extra containers.** | Everyday development |
| **`observability` profile (opt-in)** | One `grafana/otel-lgtm` container receiving metrics and traces from the services over OTLP | Load tests (NFR-4), debugging cross-service problems, demos |
| **Production** | Backend chosen at the deployment gate (hosted service or self-hosted); the services need only a changed OTLP address | After the deployment decision |

- **Dashboards and alert rules are stored as files in `deploy/observability/`** and reviewed in pull requests like code.
- **Known gap:** because Spring Boot installs no OTLP log appender by default, logs do not reach the backend automatically. In the MVP, logs are read from standard output (`docker compose logs`). Shipping logs to the backend (an appender, or a collector reading container logs) is decided when it is first needed.
- The services contain **no vendor-specific libraries**, only Micrometer and OpenTelemetry (OTLP).

## Consequences

**Positive**
- Failures can be traced from a correlation ID across logs and, when switched on, traces
- NFR-4 and the ADR-0011 targets become measurable; silent failures (outbox, dead-letter queue, sweeper) have a visible signal
- Zero extra memory by default; the heavy stack only runs when asked
- No vendor lock-in

**Negative / risks**
- **The opt-in stack is heavy** (my estimate is about 2 GB, not measured) and may not fit next to many services at once. Mitigation: run it only with the services under test; measure in Phase 3.
- **The all-in-one image is documented as development-only.** Mitigation: it is never used in production.
- **Two modes to maintain** (default and profile). Mitigation: both are exercised in CI at least by a start-up test.
- **Logs are not centralized** in the MVP. Mitigation: structured JSON on standard output is ready for any collector later.
- **Log hygiene needs constant attention:** one careless `log.info(request)` can leak a token. Mitigation: tests below and a review checklist item.
- **Spring Boot 4.1 details unverified** (log format property names, trace and span IDs in the MDC). Mitigation: confirmed at scaffolding.

## Test obligations

1. A request through the gateway produces log lines in every involved service that carry the same correlation ID, and the response returns it
2. A test sends a request containing a password, a token and a cookie and asserts that none of those values appears in any log output
3. Readiness fails when the service's own database is down and **stays ready** when RabbitMQ or another service is down
4. Management endpoints are not reachable through the gateway
5. Business metrics exist and change when the matching action happens (hold created, conflict, confirmation, outbox backlog, dead-letter depth)
6. No metric uses an identifier as a label (an automated check on metric names and tags)
7. A call gateway to service carries `traceparent`, and both sides report the same trace ID with the observability profile on
8. A security event (failed login, refresh-token reuse) produces its named `WARN` log line with a correlation ID
9. The `observability` profile starts and receives metrics from a running service (smoke test)

## Open questions for the instructor (defaults apply if unchanged)

1. **Three modes** (default: logs and health only; opt-in LGTM profile; production backend decided later)?
2. **Starting numbers:** 14-day log retention, 10% production trace sampling and the alert thresholds above, all to be tuned later (default)?

## Revisit when

- The deployment target is chosen (production backend, log shipping, alert delivery)
- The opt-in stack is too heavy on the development machine (use a hosted free tier instead)
- Spring Boot's observability support changes in a new release

## Related decisions

- **ADR-0003:** dead-letter queue depth, outbox and consumers
- **ADR-0004:** security events and secret hygiene
- **ADR-0005:** outbox tables and connection pools
- **ADR-0009:** correlation ID, `traceparent`, event envelope
- **ADR-0011, ADR-0012:** latency targets and the sweeper
- **ADR-0013:** the gateway's access log and management endpoints

## References

- Charter v1.0: NFR-4, NFR-6, section 7
- Spring blog, "OpenTelemetry with Spring Boot" (2025-11-18): https://spring.io/blog/2025/11/18/opentelemetry-with-spring-boot/ (checked 2026-11-08)
- Spring blog, "Structured logging in Spring Boot 3.4" (2024-08-23): https://spring.io/blog/2024/08/23/structured-logging-in-spring-boot-3-4 (checked 2026-11-08)
- Grafana `docker-otel-lgtm` documentation: https://pkg.go.dev/github.com/grafana/docker-otel-lgtm (checked 2026-11-08)
