# Root integration contracts

Contract status: ACTIVE
Combined cross-end integration runtime status: NOT CONFIGURED
Canonical owner: Root cross-end classification, handoff boundaries, and combined integration evidence
Activation trigger: A scoped change crosses the backend and frontend boundary or changes their shared contract
Required inputs: The owning Issue, canonical data/API documents, relevant feature specification, and verified endpoint outputs when available
Expected outputs: An explicit backend/frontend handoff and combined evidence mapped to the shared acceptance criteria
Do not infer: Deployed services, product API behavior, or successful cross-end integration from endpoint scaffold verification

## Runtime boundary

Backend scaffold verification surface: CONFIGURED
Frontend scaffold verification surface: CONFIGURED
Combined cross-end integration runtime: NOT CONFIGURED

Both endpoint scaffolds, local AI contracts, wrapper/package scripts, and verification command IDs now exist and have successful activation evidence. Use the [root command composition registry](command-registry.md) only to compose those endpoint verification IDs.

No product API, authentication exchange, shared data handoff, or integrated user-flow surface has been created or verified. Endpoint scaffold availability therefore does not change the combined cross-end integration runtime from `NOT CONFIGURED`.

## Work classification

- **backend-only:** Server-owned behavior or data changes that do not alter a frontend-consumed contract. After activation, route through the backend local owner.
- **frontend-only:** Client-owned presentation or interaction changes that do not alter a backend-provided contract. After activation, route through the frontend local owner.
- **cross-end:** A change to an API, authentication flow, authorization outcome, error meaning, data shape, idempotency behavior, or acceptance flow that requires coordinated backend and frontend work.

If either endpoint is unavailable, the cross-end task may define or review a contract but must report integration execution as `NOT CONFIGURED`, not as passed.

## Canonical contract links

Use `docs/07-data-and-api-contracts.md` for repository-wide data and API principles. Use the relevant `docs/specs/<feature>/spec.md` for durable feature behavior, concrete request and response meaning, failure semantics, and acceptance criteria. Link to those owners rather than copying their rules here.

Product, policy, UI, architecture, or quality facts remain with their corresponding `docs/` owners. This document owns only coordination across the endpoint boundary.

## Required handoff

For every cross-end change, record:

1. contract owner and acceptance criterion;
2. request, response, authentication and authorization assumptions;
3. error codes, user-visible error meaning, and retry or idempotency expectations;
4. data source of truth, optionality, ordering, precision, time, and compatibility constraints;
5. backend output and evidence required by the frontend;
6. frontend consumption and evidence required by the backend;
7. unresolved risk and the owner of the next decision.

Do not hand off guessed payloads, invented commands, or undocumented error behavior. A delegate's artifact is an input to integration review, not completion evidence by itself.

## Integration evidence boundary

Endpoint evidence stays with the endpoint command or CI run. The Pull Request combines links and maps them to shared acceptance criteria without duplicating logs.

`root.verify.scaffold` combines backend and frontend scaffold-verification evidence only. It is not combined contract, smoke, API, or user-flow evidence.

A complete cross-end evidence set contains:

- the canonical contract or specification revision;
- backend verification result and evidence;
- frontend verification result and evidence;
- combined contract, smoke, or user-flow result when an executable surface exists;
- commands actually run, observed results, remaining risks, and evidence links.

Missing endpoint or combined runtime evidence must remain `NOT RUN` or `NOT CONFIGURED` as applicable. Documentation review alone cannot prove API compatibility, authentication behavior, error handling, data consistency, or an integrated user flow.

## Activation boundary

Each endpoint scaffold and its local ownership documents were created and verified together. The root command registry may reference only the resulting `CONFIGURED` endpoint command IDs; this document never owns framework rules or literal endpoint command strings.

Cross-end integration execution may activate only after a real shared runtime, safe inputs, success and failure behavior, and combined evidence are verified. Until then, cross-end work may define or review contracts but must report integration execution as `NOT CONFIGURED`.
