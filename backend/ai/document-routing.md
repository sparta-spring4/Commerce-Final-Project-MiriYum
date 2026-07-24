# Backend document routing

Contract status: ACTIVE

Backend runtime status: CONFIGURED

## Entry route

Use this document for backend-only implementation, command execution, verification, and review. Read the root [document routing](../../ai/document-routing.md) first when work changes an API, authentication flow, error meaning, data shape, or any frontend-consumed contract.

Choose only the local owner required by the task:

- Spring Boot, Gradle, persistence, migration, or server-security implementation: [implementation guardrails](implementation-guardrails.md)
- Verified backend commands: [command registry](command-registry.md)
- Backend completion evidence and result states: [verification gates](verification-gates.md)

## Canonical owners

Repository-wide architecture remains in [system architecture](../../docs/06-system-architecture.md), data and API rules remain in [data and API contracts](../../docs/07-data-and-api-contracts.md), and quality policy remains in [quality operations and rules](../../docs/09-quality-operations-and-rules.md). Local documents apply those owners to the backend without redefining product or cross-end contracts.

## Expansion triggers

- Read a feature specification only when the task implements or changes that feature.
- Use the root [integration contract](../../ai/integration-contracts.md) for cross-end work.
- Add migration-specific material only when a migration exists.
- Activate a DB-dependent integration command only after an approved Testcontainers change satisfies ADR-002 and ADR-004.
- Do not infer CI, Docker, API smoke, deployment, runner, schema, skill, cache, or hook capability from the backend scaffold.
