# ADR-0005: Data storage, database topology and migrations

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-08 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

Every service owns its data and no service may read another service's tables (NFR-2). ADR-0012 depends on PostgreSQL features (row locks, conditional updates, `SKIP LOCKED`, the database clock). ADR-0003 and ADR-0006 require outbox tables and processed-event records in each service. The development machine is a Windows laptop with limited free memory (observed about 3.8 GB free of 16 GB), and the deployment target is still undecided (charter section 7).

This ADR decides: **(A)** engine and topology, **(B)** the migration tool, **(C)** how services access their database, **(D)** how we test against it, and **(E)** shared schema conventions.

## Decision drivers

1. Real isolation between services' data (NFR-2), checked automatically
2. Behavior identical to production, because ADR-0012 relies on PostgreSQL specifics
3. Memory and operations cost small enough for one developer and one laptop
4. Schema changes that are reviewable, repeatable and safe to deploy
5. No technology before a feature needs it (no cache, no search engine in the MVP)

## Facts checked on 2026-11-08

- **PostgreSQL 18** is the newest released major version (current minor 18.6, first released 2025-09-25) and is supported until **2030-11-14**. PostgreSQL 19 is at release-candidate stage and is not a general release. PostgreSQL 14 reaches end of life on 2026-11-12. Source: postgresql.org versioning policy.
- Spring Boot 4 appears to ship Flyway support as a separate starter (indicated by a published Spring Boot 4 migration recipe named "Add spring-boot-starter-flyway if using Flyway"). **I could not read that page's content, so this is unverified**; it must be confirmed against the Spring Boot 4.1 documentation when the first service is scaffolded.

## Part A: engine and topology

### Option A1: One PostgreSQL instance per service, always
- Pros: strongest isolation; independent failure and scaling
- Cons: five database servers on a laptop and on a small server; memory and operations cost out of proportion for the MVP

### Option A2: One PostgreSQL instance per environment, one **database** per service, one **role** per service
- Pros: light enough for the laptop and a single small server; PostgreSQL does not allow querying across databases within one instance without extra extensions that we will not install, so data is genuinely separated; each role can be given access to its own database only
- Cons: shared instance means a shared failure domain and shared resources (a noisy service can slow the others)

### Option A3: One database with one **schema** per service
- Pros: simplest
- Cons: weak isolation, because cross-schema queries and joins work with a single connection; invites the coupling NFR-2 forbids

## Part B: migration tool

### Option B1: Flyway (versioned SQL files)
- Pros: plain SQL, easy to review in a pull request; simple mental model; strong Spring Boot support
- Cons: no automatic rollback in the free edition; some features are paid

### Option B2: Liquibase (XML/YAML/SQL changelogs)
- Pros: database-independent changelogs; rollback definitions
- Cons: more abstraction than we need; changelogs are harder to review than SQL

### Option B3: Let Hibernate create or update the schema automatically
- Rejected: unreviewable, unsafe in production and cannot express the constraints, indexes and locking rules that ADR-0012 depends on

## Part C: data access

### Option C1: Spring Data JPA (Hibernate) everywhere
- Pros: fast for ordinary CRUD
- Cons: hides the SQL that ADR-0012 and ADR-0006 require to be explicit and exact (ordered row locks, conditional updates)

### Option C2: Explicit SQL everywhere (for example Spring's JDBC client)
- Pros: full control and transparency
- Cons: more code for simple CRUD in Identity, Catalog and Notification

### Option C3: JPA for ordinary CRUD, explicit SQL for correctness-critical paths
- Pros: productivity where it is safe, precision where it matters
- Cons: two styles; needs a clear rule for which applies where

## Part D: tests against the database

### Option D1: An in-memory database such as H2
- Rejected: its locking and SQL behavior differ from PostgreSQL, so tests could pass while ADR-0012's guarantees fail in production

### Option D2: Real PostgreSQL in a throwaway container for tests (Testcontainers)
- Pros: tests exercise the real engine, including concurrency; same container image family as production
- Cons: slower than in-memory; needs Docker in CI and on the laptop (already a requirement)

## Decision

### Engine and topology (A)

- **PostgreSQL 18** (latest 18.x minor, pinned in the compose file and deployment). Revisit major versions before the support end date, not before.
- **Option A2:** one PostgreSQL instance per environment, with **one database and one role per service**. Each service's role may connect to its own database only; `CONNECT` is revoked from everyone else. Cross-database extensions are not installed.
- An **initialization script in `deploy/`** creates the databases and roles for local development, so the whole stack starts with one command (NFR-8).
- Services read their connection settings from configuration and **never assume the database is on the same server as another service's database**. Moving a service to its own instance later must need configuration changes only.
- Credentials come from the environment or a secret store, never the repository (NFR-7, ADR-0004). Each service gets its own password.
- **Isolation is verified by an automated test** (see obligations), because "one instance" must not slowly become "one shared database".
- **No Redis or other cache** and **no separate search engine** in the MVP. Event search (FR-4) uses PostgreSQL indexes and full-text search.

### Migrations (B)

- **Flyway**, with plain SQL migrations in each service's own folder; each service migrates only its own database.
- **Forward-only and immutable:** a migration that has been merged is never edited; fixes are new migrations.
- **Backward-compatible changes (expand, then contract):** a release may add columns and tables; removing or renaming something takes two releases, so the previous version of the code keeps working during a deployment.
- Migrations run **when the service starts** in development; for production the deployment ADR decides whether they run as a separate pipeline step. Flyway's own locking keeps concurrent starts safe.
- Hibernate's automatic schema generation is **off**; at most it validates that mapped entities match the schema.
- Starter and library versions are verified against the Spring Boot 4.1 documentation and pinned when the first service is scaffolded.

### Data access (C)

- **Option C3.** Spring Data JPA for ordinary CRUD (Identity, Catalog, Notification, Payment records). **Booking's seat operations and every outbox or "processed event" operation use explicit, reviewed SQL** inside a single repository class per concern, so ADR-0012's rule "one repository method owns the expired-hold logic" has exactly one home.
- No service-to-service database access of any kind (NFR-2). Foreign keys exist only **within** a service's own database; references to another service's data are plain identifiers with no database constraint.

### Tests (D)

- **Option D2.** Integration tests run against real PostgreSQL in Testcontainers, using the same major version as production. **H2 and similar are not used.**

### Conventions (E)

- **Identifiers:** UUIDs generated by the application; no sequential IDs exposed in public APIs.
- **Time:** timestamps are `timestamptz`, stored and exchanged in UTC; deadlines use the database clock (ADR-0012).
- **Money:** integer amount in the smallest currency unit plus a currency code. **Never floating point.**
- **Naming:** `snake_case` for tables and columns; plural table names; every table has `created_at`.
- **Constraints belong in the database:** `NOT NULL`, unique, check and foreign-key constraints are defined in migrations, not only in application code. Seat state, for example, is restricted to its three allowed values.
- **Optimistic locking:** a `version` column on aggregates that two requests could update at once, where ADR-0012's explicit locking does not already apply.
- **Outbox table** (ADR-0003), in every publishing service: event ID, aggregate type and ID, event type, payload (JSON), created time, published time (empty until sent). The relay reads unpublished rows in batches using `FOR UPDATE SKIP LOCKED`.
- **Processed-events table** (ADR-0003, rule 2), in every consuming service: event ID with a unique constraint and the processing time.
- **Indexes** are added with the query that needs them and justified in the pull request; the seat table's lookup by event and state is designed with Booking.
- **Connection pools** are small by default (single digits per service) so the total stays far below PostgreSQL's connection limit; sizes are tuned from measurements, not guesses.
- **Personal data:** only what is needed. Passwords are stored as hashes only (ADR-0004). Payment card data is never stored in any service; the MVP payment provider is simulated.
- **Backups and retention** are decided at the deployment gate; until then development data is disposable.

## Consequences

**Positive**
- Genuine data separation with a fraction of the memory of five database servers
- Tests that exercise the same engine and locking behavior as production
- Reviewable, repeatable schema changes and a safe deployment pattern
- ADR-0012 and ADR-0006 guarantees are implemented where they can be read and tested

**Negative / risks**
- **Shared failure domain:** if the PostgreSQL instance goes down, every service is affected, which weakens the independence NFR-3 aims for. Mitigation: accepted for the MVP, documented, and revisited at the deployment gate; the configuration allows splitting instances without code changes.
- **Noisy neighbor:** one service's heavy queries can slow the others. Mitigation: small pools, query reviews, and load tests against the Booking hot path (NFR-4).
- **Isolation can erode** (someone grants a role extra access). Mitigation: automated isolation test; privileges reviewed in pull requests.
- **Two data-access styles** may confuse. Mitigation: the rule above, enforced in review.
- **Container-based tests need Docker** on every machine and in CI, and they run slower. Accepted; tests are layered so most are fast unit tests.
- **Unverified detail:** the Spring Boot 4 Flyway packaging, to be confirmed at scaffolding.

## Test obligations

1. Each service's role can connect to its own database and **cannot** connect to another service's database (NFR-2)
2. Migrations apply cleanly to an empty database in CI, and from the previous released schema to the current one
3. Application start fails fast if migrations fail or the schema does not match
4. Concurrency tests for Booking run against real PostgreSQL (ADR-0012 obligations)
5. Outbox relay: a batch of unpublished events is published exactly once per relay, even with two relays running
6. A scan confirms no database credentials in the repository (NFR-7)

## Open questions for the instructor (defaults apply if unchanged)

1. **Topology:** accept one PostgreSQL instance per environment with one database and role per service for the MVP (default)?
2. **Backups and data retention:** decide at the deployment gate (default), or set requirements now?

## Revisit when

- A service needs different resource guarantees, or an instance failure becomes unacceptable (split into separate instances)
- PostgreSQL 18 approaches end of support (2030) or a required feature needs a newer version
- JPA proves a poor fit for a service beyond the Booking hot paths

## Related decisions

- **ADR-0003:** outbox and idempotent consumers
- **ADR-0004:** credentials and secrets
- **ADR-0006, ADR-0012:** state machine and seat-state rules implemented in explicit SQL
- **ADR-0007:** Java, Spring Boot and Maven (Flyway and Testcontainers versions are pinned with it)
- **ADR-0009:** idempotency-key conventions

## References

- Charter v1.0: NFR-2, NFR-3, NFR-4, NFR-7, NFR-8, section 7
- PostgreSQL versioning policy: https://www.postgresql.org/support/versioning (checked 2026-11-08)
