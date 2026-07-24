# Verification and completion

Contract status: ACTIVE

## Result vocabulary

Allowed results: PASS | FAIL | BLOCKED | NOT RUN | NOT CONFIGURED | NOT APPLICABLE

- `PASS`: the stated command or review ran against the stated scope and met its expected outcome.
- `FAIL`: the command or review ran and did not meet its expected outcome.
- `BLOCKED`: verification cannot proceed because a named dependency, permission, decision, or environment condition prevents it.
- `NOT RUN`: an applicable check was not executed; record why and the resulting risk.
- `NOT CONFIGURED`: the required execution surface, command, environment, or workflow does not exist or has not been verified.
- `NOT APPLICABLE`: the check does not apply to the change; record the scope-based reason.

Use only observed results. A `TEMPLATE`, plan, documentation contract, file name, or expected output cannot produce `PASS`.

## Verification level by change type

| Change type | Minimum verification |
|---|---|
| Text or link only | Targeted content contract, link/path ownership review, formatting check, and diff review |
| Product, policy, feature, or API contract | Canonical-owner review, acceptance-criterion trace, cross-document consistency, and applicable contract validation |
| Executable implementation | Focused automated tests, broader affected test suite, static/build checks, and applicable runtime behavior |
| Data or migration | Schema and migration validation, compatibility and rollback review, and data integrity evidence |
| Security, authentication, or external side effect | Negative-path tests, permission and secret review, safe-environment evidence, and explicit risk review |
| Cross-end behavior | Endpoint evidence plus combined contract or user-flow evidence under `ai/integration-contracts.md` |

Run the narrowest relevant check first, then broader gates justified by impact. A missing runtime remains `NOT CONFIGURED`; do not invent a product test for documentation-only work.

## QA

QA owns evaluation of user-observable acceptance behavior and meaningful failure paths. It records the environment, inputs, commands or actions, observed results, defects, and evidence. QA does not replace implementation tests, canonical contract review, or CI.

Until repeatable QA work has an independent verified workflow or owner, this section is the canonical QA contract. A separate QA document is deferred.

## CI

CI owns the result and logs of its actual run. A workflow is active only after it exists and reproduces the required gates on a clean checkout. A check is required only after repository settings are also verified. Configuration text, a local run, or a planned workflow is insufficient to claim active/required CI.

CI status is `NOT CONFIGURED`. Local endpoint verification and root command composition do not activate CI. Pull Requests link to CI evidence rather than copying logs into a permanent work log.

## reviewer

The reviewer maps the diff to the Issue scope and each acceptance criterion, checks ownership and dependency boundaries, examines security, data, migration and rollback risks, and assesses whether verification matches the change type. Review must challenge unsupported result labels and confirm that unrelated user changes were not included.

Approval is review evidence, not a substitute for an applicable executable gate.

## Issue completion

The Issue may complete only when its acceptance criteria are resolved, the assignee and delegated outputs are reconciled, required canonical documents are updated, applicable verification is represented with the allowed vocabulary, and unresolved risks are explicit. GitHub Project's single `Status` field remains the only lifecycle state; no repository mirror is created.

## done claim

Every done claim must include all four fields:

- Commands: exact commands or reviews actually performed
- Results: one allowed result for each command or review
- Risks: remaining uncertainty, skipped coverage, rollback or follow-up risk
- Evidence: output summary, CI/PR link, artifact, or review reference that supports the result

Refuse a done claim when any field is absent, when a `PASS` has no execution evidence, or when the claim depends on an unverified runtime. Do not describe a check as configured, CI as active/required, or work as complete without the corresponding actual files, settings, run, and successful evidence.

## Failure correction and gate return

When verification fails or a completion claim lacks evidence:

1. label the observed result accurately;
2. identify the failing gate and root condition;
3. limit correction to the authorized scope;
4. rerun the originally required gate after the correction;
5. preserve the new result and evidence without erasing the prior risk.

A correction route never bypasses the original QA, CI, reviewer, or acceptance gate. Repeated verification avoidance or evidence-free completion claims may activate a separate corrective runbook only through the matrix below.

## Deferred activation matrix

| Deferred responsibility | Initial state | Activation trigger and required boundary |
|---|---|---|
| Machine-readable project state, machine registry, and JSON schema | `NOT CONFIGURED` | Actual endpoint commands and output formats are stable and verified; the schema must validate real fixtures |
| command runner | `NOT CONFIGURED` | Repeated commands, failure fixtures, exit behavior, and evidence format are verified |
| verification runner | `NOT CONFIGURED` | Multiple gates have stable inputs, ordering, failure behavior, and reproducible outputs |
| API smoke verifier | `NOT CONFIGURED` | A real API scaffold, environment contract, safe data, success case, and failure case are verified |
| failure triage artifact | `NOT CONFIGURED` | Repeated failures show a stable classification and actionable handoff format |
| Separate QA, CI, reviewer, Issue completion, and done-claim documents | `NOT CONFIGURED` | A section here becomes insufficient because an independent workflow or owner is demonstrated |
| `lazycodex-runbook.md` | `NOT CONFIGURED` | Verification avoidance or evidence-free completion claims repeat and require an independent correction procedure |
| Workflow cache and cache policy | `NOT CONFIGURED` | Repeated discovery cost and stale-context failures are measured, with invalidation evidence |
| Native runtime adapter and provenance | `NOT CONFIGURED` | Remote or CI execution demonstrates a host-trust and durable-provenance requirement |
| CI workflow and required check | `NOT CONFIGURED` | Local commands and gates reproduce on a clean checkout, then repository required-check settings are verified |
| Optional local Git hook | `NOT APPLICABLE` | A verified common script exists and repeated local mistakes are observed; any hook is a thin optional wrapper and never replaces CI |

### Deferred skill contracts

The initial state of all seven skill contracts and the deferred `ai/skills/README.md` selection catalog is `NOT CONFIGURED`. The skill files are not created in Phase 1. Create and activate an individual skill only after repeated workflow verifies its inputs, outputs, failure behavior, and handoff. Create and activate the selection catalog only after verified skill contracts exist and its selection, ownership, and activation rules are themselves verified; do not create it merely to list deferred names.

- `repo-intake.md`: repeated repository intake needs a stable bounded context and ownership result.
- `command-runner.md`: repeated command execution has verified invocation and evidence formats.
- `verification-runner.md`: repeated gate orchestration has verified ordering and failure behavior.
- `api-smoke-verifier.md`: a real endpoint supports repeatable safe smoke checks and negative cases.
- `failure-triage.md`: recurring failures have a proven classification and next-owner handoff.
- `docs-sync.md`: recurring canonical-document drift has a verifiable reconciliation workflow.
- `review-gate.md`: recurring review inputs and outputs can be enforced without duplicating the Pull Request.

## Endpoint scaffold and later boundary

The backend and frontend scaffolds, local AI contracts, wrapper/package scripts, and endpoint execution evidence now exist. Their `CONFIGURED` command IDs may be composed through `ai/command-registry.md`. Root composition is delegation and evidence aggregation only; it does not activate a runner, CI, API smoke, or cross-end integration runtime.

Runner, schema, CI, skill, `lazycodex-runbook.md`, cache, or adapter work moves to a separate plan only after the corresponding repeated workflow or failure evidence satisfies the matrix. The endpoint scaffold work does not create those artifacts.

A mandatory Git hook is prohibited. Consider an optional thin wrapper only after a common script is verified and repeated local mistakes are documented; it must not own policy or replace CI.
