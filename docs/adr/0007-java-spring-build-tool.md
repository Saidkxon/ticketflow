# ADR-0007: Java, Spring Boot and build tool versions

| | |
|---|---|
| **Status** | Accepted |
| **Date** | 2026-11-01 |
| **Deciders** | Saidafzal (instructor), Claude (architect) |

## Context

The backend uses Java and Spring Boot (charter section 7), and the charter says exact versions must be fixed after checking what is current. A new project should not start on a version that is already behind, because upgrades later cost more than choosing well now.

Facts checked on 2026-11-01:

- **Java 25** is the current long-term-support (LTS) release; Java 21 is the previous LTS (Oracle JDK downloads page).
- **Spring Boot 4.1.0** reached general availability on 2026-06-10, and **4.1.1** was released on 2026-08-20 (Spring blog).
- Spring Boot 4.1 requires at least Java 17 and documents support up to Java 25 (and 26 in the 4.1.x snapshot docs). It supports Maven 3.6.3+ and Gradle 8.14+ / 9.x. Embedded servers are Tomcat 11 and Jetty 12.1. Undertow is not supported.
- **Caveat:** the system-requirements pages I could retrieve were milestone and snapshot versions of the 4.1 docs. The exact requirements must be re-confirmed on the final 4.1.x page when the first service is scaffolded.

## Decision drivers

1. Start on currently supported versions with the longest runway
2. Be able to follow official documentation
3. Simple, reviewable builds on a Windows development machine and in CI
4. Reproducible builds (same result locally, in CI and in containers)

## Options considered

### Java: 25 LTS vs 21 LTS
- **25 LTS:** newest LTS, supported by Spring Boot 4.1; longest support runway; no upgrade debt at the start.
- **21 LTS:** more third-party tutorials and library compatibility history, but it is already the previous LTS.

### Spring Boot: 4.1.x vs 3.5.x
- **4.1.x:** current line, built on Spring Framework 7, longest support runway.
- **3.5.x:** older line with more tutorials, but starting a new project on it creates an upgrade to do within the project's lifetime.

### Build tool: Maven vs Gradle
- **Maven:** declarative XML, very common in enterprise Java, easy for a reviewer to read, strong convention over configuration.
- **Gradle:** faster incremental builds and flexible scripting, but build logic is code, which is harder to review and easier to over-engineer.

## Decision

- **Java 25 (LTS)**, using a free OpenJDK distribution (Eclipse Temurin) locally and in container images.
- **Spring Boot 4.1.x**, latest patch at scaffolding time (4.1.1 as of today).
- **Maven**, always via the **Maven Wrapper** (`mvnw`) so everyone and CI use the same version; minimum Maven 3.9.
- A root parent POM centralizes dependency and plugin versions (see ADR-0001, rule R4). This includes the versions of Flyway, Testcontainers and the Spring Cloud release train, which are pinned here when the first service is scaffolded (ADR-0005, ADR-0013).
- Versions are pinned in the build, never "latest".

## Consequences

**Positive**
- Longest support runway; no near-term forced upgrade
- Reviewable builds; same tool versions everywhere through the wrapper

**Negative / risks**
- Spring Boot 4 is a recent major version, so many online tutorials and Stack Overflow answers still target Boot 3. Mitigation: treat official Spring documentation as the source of truth; the architect flags version-specific differences in reviews.
- Some third-party libraries may lag behind Java 25 or Boot 4. Mitigation: check compatibility before adopting any new dependency and record non-obvious choices in the PR.
- Patch versions move. Mitigation: a scheduled dependency-update check in CI (Phase 2).

## Revisit when

- A required library does not support Java 25 or Spring Boot 4.1
- Spring Boot 4.1 reaches the end of open-source support (check the support table on spring.io before each phase starts)

## References

- Oracle JDK downloads: https://www.oracle.com/java/technologies/downloads/ (checked 2026-11-01)
- Spring Boot 4.1.0 announcement: https://spring.io/blog/2026/06/10/spring-boot-4/
- Spring Boot 4.1.1 announcement: https://spring.io/blog/2026/08/20/spring-boot-4-1-1-available-now/
- Spring Boot system requirements: https://docs.spring.io/spring-boot/4.1/system-requirements.html
- Charter v1.0: section 7