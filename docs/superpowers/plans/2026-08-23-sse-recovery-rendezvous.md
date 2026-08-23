# SSE Recovery Issue-comment Rendezvous Implementation Plan

> Owner: #357. Execute with test-driven development and verify every observed result.

**Goal:** Make staging SSE recovery mutation occur inside the fixed Valkey interruption by coordinating the prepared GitHub Actions workflow and local k6 harness through strictly scoped Issue comments and a shared bounded epoch.

**Architecture:** The control workflow pre-validates and authenticates, waits for an actor-bound FIRE marker, schedules the reviewed SSM interruption, and posts a bot-authored ARMED marker only after SSM is running. The k6 recovery path accepts only the matching public marker and waits to a fixed offset inside the scheduled ten-second outage. Existing automatic Valkey recovery and fixture cleanup remain authoritative.

**Technology:** GitHub Actions YAML, Bash, Python unittest, k6 JavaScript, xk6 SSE, GitHub REST API.

---

## Task 1: Lock the workflow rendezvous contract

**Files:**
- Modify: `.github/workflows/staging-load-test-control.yml`
- Modify: `.github/workflows/staging-valkey-control.yml`
- Modify: `scripts/test-staging-load-test-control-workflow.py`

1. Add failing contract tests for interrupt-only Issue/UUID inputs, `issues: write`, actor/time/scope-bound FIRE parsing, OIDC-before-wait ordering, SSM `InProgress` before ARMED, and rejection of arbitrary command/duration inputs.
2. Run `python scripts/test-staging-load-test-control-workflow.py` and confirm the new tests fail for the missing rendezvous contract.
3. Add strict input validation and delegate the validated scope only to the Valkey reusable workflow.
4. Implement the bounded FIRE polling and bot ARMED acknowledgement without printing comment bodies.
5. Re-run the workflow contract tests and confirm they pass.

## Task 2: Schedule the fixed Valkey interruption safely

**Files:**
- Modify: `scripts/staging-valkey-control.sh`
- Modify: `scripts/test-staging-valkey-control.py`

1. Add failing tests for malformed, late and excessively future epochs failing before Valkey stop, plus a valid scheduled interrupt retaining fixed ten-second recovery.
2. Run `python scripts/test-staging-valkey-control.py` and confirm the new tests fail.
3. Add interrupt-only epoch validation and bounded waiting before the existing stop/trap/recovery sequence.
4. Ensure `recover` rejects scheduled input and no epoch or environment value is logged.
5. Re-run the Valkey tests and shell syntax validation.

## Task 3: Gate k6 mutation on the ARMED marker

**Files:**
- Modify: `performance/k6/sse/config.js`
- Modify: `performance/k6/sse/recovery.js`
- Modify: `performance/k6/sse/main.js`
- Modify: `performance/k6/tests/sse-config-contract.js`
- Modify: `performance/k6/tests/sse-recovery-contract.js`

1. Add failing config tests for explicit approval, fixed GitHub API host, repository/Issue/run/UUID validation, staging-only mode, and fixed-delay/rendezvous mutual exclusion.
2. Add failing recovery tests for exact bot marker parsing, wrong scope/author/stale epoch rejection, bounded polling timeout before mutation, and mutation ordering at the shared epoch offset.
3. Run the focused k6 contract tests with the repository-pinned k6 command and confirm RED.
4. Implement frozen rendezvous configuration without preserving credentials or marker bodies.
5. Implement unauthenticated Issue-comment polling, exact marker validation and bounded shared-epoch wait as injected recovery dependencies.
6. Wire the staging recovery profile to the rendezvous path while preserving the local fixed-delay path.
7. Re-run focused tests and confirm GREEN.

## Task 4: Align operator documentation

**Files:**
- Modify: `performance/k6/README.md`
- Modify: `docs/deployment/staging-load-test-operator-runbook.md`
- Modify: `docs/deployment/sse-runtime-runbook.md`

1. Replace the dispatch-after-READY staging sequence with pre-arm workflow → start k6 → FIRE comment → ARMED epoch flow.
2. Document exact non-secret marker formats, bounded failure states, fresh fixture preservation and mandatory rollback.
3. Keep production, direct EC2 shell, arbitrary SSM and credential-comment paths explicitly forbidden.

## Task 5: Verify scope, safety and regression

1. Run the full staging control workflow contract and Valkey runtime tests.
2. Run all SSE k6 contract suites using the repository-pinned image/runtime.
3. Run `bash -n scripts/staging-valkey-control.sh`, YAML parsing where configured, and `git diff --check`.
4. Confirm the diff contains only the #357 allowlist and scan the diff for credential literals and forbidden Authorization use on the GitHub comment poll.
5. Record actual commands, results, unrun staging verification and remaining risk in the PR evidence. Do not claim #357 complete until a post-merge fresh-fixture staging recovery passes.
