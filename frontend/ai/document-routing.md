# Frontend document routing

Contract status: ACTIVE

## Start here

Read `../AGENTS.md`, then select only the documents needed for the change:

- React, TypeScript, Vite, accessibility, client state, or frontend-only
  verification: use the local contracts in this directory and
  `../../docs/08-ui-and-frontend-guidelines.md`.
- API shapes, error meanings, time, money, or idempotency:
  use `../../docs/07-data-and-api-contracts.md`.
- Cross-end behavior, authentication, authorization, or a shared acceptance
  flow: return to `../../AGENTS.md`, `../../ai/document-routing.md`, and
  `../../ai/integration-contracts.md`.
- Verification and done claims: use `verification-gates.md`,
  `../../docs/09-quality-operations-and-rules.md`, and
  `../../ai/verification-and-completion.md`.

## Ownership boundary

This frontend route owns React rendering, TypeScript and Vite configuration,
accessible client behavior, frontend command definitions, and frontend
verification evidence. It does not own backend behavior, repository-wide API
semantics, product policy, infrastructure, CI, or deployment.

Do not invent routes, payloads, feature folders, API clients, or external
runtime state. Route unresolved cross-end decisions to their root owner.
