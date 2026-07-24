# Backend implementation guardrails

Contract status: ACTIVE

## Build and application boundary

- Use Java 21, Spring Boot 4.1.0, and the committed Gradle 9.6.1 Wrapper.
- Keep one Spring Boot application under `com.miriyum`.
- Apply the domain and three-layer boundaries from [ADR-001](../../docs/adr/ADR-001-domain-packages-three-layer.md).
- Create domain packages and their controller, service, repository, entity, DTO, or exception children only when real implementation requires them.
- Do not add placeholder controllers, sample endpoints, empty packages, or speculative abstractions.

## Persistence and migration

- MySQL is the persistence source of truth; Flyway owns schema changes once migrations exist.
- Read database connection values from environment input. Never commit credentials or local default passwords.
- Do not use H2 as MySQL evidence.
- DB-dependent integration remains `NOT CONFIGURED`. Consider Testcontainers only for the activation triggers in [ADR-002](../../docs/adr/ADR-002-staged-technology-adoption.md) and [ADR-004](../../docs/adr/ADR-004-scaffold-toolchain-and-test-baseline.md).

## Server security and boundaries

- Spring Security owns the server authentication and authorization boundary once a scoped feature defines it.
- Do not invent users, roles, authentication flows, public routes, or secret handling.
- Keep controllers at the HTTP boundary, services at the use-case and transaction boundary, and repositories at the persistence boundary.

## Deferred capabilities

Docker, Compose, CI, deployment, API smoke, runners, schemas, skills, caches, and hooks are not configured by this scaffold. Add them only through their canonical activation route and a separately approved allowlist.
