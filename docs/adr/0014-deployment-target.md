# ADR-0014: Deployment target: one VPS with Docker Compose

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-14 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

The charter (section 7) requires the deployment target to be chosen before the walking skeleton is declared done, because CI/CD design depends on it. Several ADRs deferred their production details to this point: TLS and the production edge component (ADR-0004, ADR-0013 rule G10), log retention and the production observability backend (ADR-0008), and backups (ADR-0005). The charter also states a minimal budget, a team of one developer at 10-15 hours per week, and NFR-8: the whole system starts with one documented command, tested in CI.

**Measured on 2026-11-14** (Docker Desktop on the development laptop, idle, empty database): gateway 279 MiB, Catalog 258 MiB, PostgreSQL 35-62 MiB. The web container was not measured. The stack already starts with `docker compose -f deploy/compose.yaml --profile app up -d --build --wait` and the `stack-smoke` CI job runs it on every pull request.

**Estimate, not a measurement:** six Spring Boot services at about 270 MiB each is about 1.6 GB. PostgreSQL, RabbitMQ (from Phase 4b), the web container, the edge proxy and the operating system add roughly 1 GB or more. Real use will raise these numbers.

**A trap found while writing this ADR:** the service images set `-XX:MaxRAMPercentage=75`. Without a container memory limit that percentage applies to the **whole host**, so six JVMs on a small server could each try to grow to 75% of its RAM. Production must set a memory limit per container (rule R5).

## Decision drivers

1. Low and predictable monthly cost (charter: minimal budget).
2. Matches what is already built and tested: the same Compose stack runs locally, in CI and in production.
3. Small operational load for one developer, but with real operations to practise (the project's learning goal).
4. A clear way to ship, roll back and secure it.
5. Room to move to something bigger later without rewriting the services.

## Options considered

### Option A: One Linux VPS running Docker Compose, with a reverse proxy for HTTPS
Pros: cheapest; production equals the tested local stack; every part is visible and learnable (firewall, backups, deploys). Cons: one machine is a single point of failure (the charter excludes high availability); the developer is the operator (patching, backups).

### Option B: A managed container platform (Fly.io, Render, Railway and similar)
Pros: less server work; built-in TLS and deploys. Cons: each of six services, PostgreSQL and RabbitMQ is configured separately; free tiers are small for this many JVMs, so cost is less predictable; the platform's own concepts replace the Compose file that CI already tests.

### Option C: Kubernetes (managed, or k3s on one server)
Pros: the industry standard; rolling updates and self-healing. Cons: a large amount to learn and run before any feature ships; the managed control plane costs money, and k3s on one VPS gives most of the complexity without the benefits. Overkill for the MVP scope.

### Option D: Local only for now
Breaks the charter gate and leaves "deployable by pipeline" untested. Rejected.

## Decision

**Option A.** TicketFlow runs on **one Linux VPS with Docker Engine and the Compose plugin**. The rules:

**R1. Server and provider.** A Linux VPS with **8 GB RAM recommended** (4 GB is the minimum, only with a swap file and tight limits, and is expected to be too small once all six services and RabbitMQ run). The **provider is chosen in a separate step and is not decided here.** Criteria: payment works from Uzbekistan, a fixed monthly price (or a hard budget alert), snapshots, and a location with acceptable latency. Candidates: the AWS account already in use for another project (EC2), and Hetzner Cloud (cheap, but see References: new-customer restrictions were reported). A server is created only once the pipeline is ready to use it.

**R2. Edge.** A **Caddy** container is the only thing published on the server's ports (80 and 443). It obtains and renews the TLS certificate automatically and forwards everything to the **web container**, which serves the static files and forwards `/api/**` to the gateway, exactly as in the local stack. So the production edge is the same web container as today plus Caddy for TLS, and this settles G10 and the "TLS ends at the gateway" wording in ADR-0004: **TLS ends at Caddy**; traffic behind it travels on the private Docker network. The gateway, services, PostgreSQL and RabbitMQ publish **no** host ports in production. A domain name is required for the certificate; a free dynamic-DNS subdomain is acceptable for this project.

**R3. Images.** CI builds each image once and pushes it to the **GitHub Container Registry**, tagged with the commit SHA. The server never builds images.

**R4. Deploying.** A GitHub Actions job runs after the required checks pass on `main`. It is gated by a GitHub **environment named `production` with a required reviewer** (a manual approval), then connects to the server with SSH as a dedicated `deploy` user using a key that lives only in GitHub secrets, runs `docker compose pull` and `up -d --wait` with the new SHA, and runs the smoke test against the public address. **Rollback is redeploying the previous SHA.** Members of the `docker` group are effectively root on the server, so this user and key are treated as highly privileged.

**R5. Production Compose file.** `deploy/compose.prod.yaml` is used with the base file. It replaces `build` with `image`, sets a **memory limit for every container**, publishes only Caddy's ports, and limits container log size and count (this is the "size-limited local logs" of ADR-0008). Log retention on the server starts at **14 days** (ADR-0008 starting point). Logs stay in the containers' own log files at first; a log service is added only when needed.

**R6. Secrets.** Production passwords live in a file on the server (`.env`, mode 600, outside the repository). They are never committed and never printed in CI logs (NFR-7).

**R7. Backups.** A nightly `pg_dump` of every database, copied **off the server** to cloud storage, keeping 7 daily and 4 weekly copies. A backup that exists only on the same disk is not a backup. **A restore is tested once** before the project is called production-ready. Exact scripts and storage are written in Phase 5.

**R8. Server basics.** SSH by key only, no password login, no root login; a firewall that allows only ports 22, 80 and 443; automatic security updates; a budget alert on the provider account.

## Consequences

**Positive**
- Production uses the same Compose stack that CI already tests, so "works locally" and "works in production" stay close.
- Predictable, low cost; no new platform to learn before features.
- Closes the deferred items: TLS and edge (R2), log size and retention (R5), backups (R7).

**Negative / risks**
- **Single point of failure and downtime during deploys.** Accepted for the MVP (high availability is out of scope in the charter); a restart takes about a minute. Mitigation: smoke test after each deploy, quick rollback.
- **Memory is tight on a small server.** Mitigation: 8 GB recommended, a limit per container, measure after each phase and record the numbers here.
- **The developer operates the server.** Mitigation: R8, a short runbook in `deploy/` written with the first deployment, and restore tests.
- **Provider access is uncertain** (account verification, payment from Uzbekistan, availability). Mitigation: the provider is a separate, reversible choice; nothing in the pipeline depends on which provider is picked.
- **SSH deploy access is powerful.** Mitigation: a dedicated user, a key stored only as a GitHub secret, and a manual approval on the `production` environment.

## Revisit when

- Measured memory no longer fits one server, or a deploy-time outage becomes unacceptable (look at a second server or a platform with rolling updates).
- Real users, uptime promises or a need for horizontal scaling appear (Option C).
- The provider's price or availability changes materially.
- A service is shown to need its own PostgreSQL instance (ADR-0005).

## References

- [docs/00-project-charter.md](../00-project-charter.md), sections 7 and 14 (D3, D10)
- [ADR-0004](0004-authentication.md), [ADR-0005](0005-data-and-migrations.md), [ADR-0008](0008-observability.md), [ADR-0013](0013-api-gateway.md)
- Memory measurements: `docker stats --no-stream` on the development laptop, 2026-11-14 (see Context).
- Caddy official Docker image: automatic HTTPS needs ports 80 and 443 and a persisted `/data` volume. Source: Docker Hub, "caddy" (checked 2026-11-14).
- Hetzner Cloud: prices rose in April and June 2026; about 4 GB plans were listed near 6 EUR per month and about 8 GB near 9 EUR per month excluding VAT (October 2026 data); new cloud servers were reported restricted for new customers since 26 June, and identity verification is sometimes required. Source: Better Stack, "Hetzner Cloud Review 2026" (checked 2026-11-14; a secondary source, **verify on the provider's own pages before choosing**).
