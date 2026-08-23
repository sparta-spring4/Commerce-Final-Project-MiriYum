# SSE Recovery Issue-comment Rendezvous Design

## Status

- Date: 2026-08-23
- Owner Issue: #357
- Stage: 고도화 검증
- Scope: staging k6 recovery control only

## Problem

The current recovery harness opens the SSE stream, emits `SSE_RECOVERY_READY`, waits a
fixed arm delay, and then performs one approved `WAITING -> CALLED` mutation. The
operator dispatches the Valkey interruption workflow after seeing the ready message.

Two staging attempts showed 51–59 seconds between readiness and SSM submission. That
delay consumes almost the entire 60-second arm bound, so a fixed delay cannot prove
that the mutation occurred during the fixed ten-second Valkey interruption. Extending
the delay also pushes the stream toward its client timeout and does not remove GitHub
runner scheduling variance.

## Decision

Use a one-time GitHub Issue comment rendezvous. The workflow is prepared before k6 is
started, and the workflow schedules the interruption only after k6 has emitted its
ready signal. A shared bounded epoch, acknowledged by `github-actions[bot]`, aligns the
remote interruption and local mutation without passing GitHub or AWS credentials to
k6.

The workflow and k6 treat Issue comment bodies strictly as untrusted data. Only exact
allowlisted marker formats are parsed; comment text is never evaluated as a command.

## Control Flow

1. The operator creates a UUID rendezvous ID and dispatches `interrupt-valkey` from
   `dev` with the approved backend SHA, Issue number, and rendezvous ID.
2. The workflow validates the caller, immutable SHA, Issue number, UUID, current
   successful staging deployment, and AWS configuration. It completes checkout and
   OIDC before waiting for a fire marker.
3. The workflow records its wait-start timestamp and polls only comments created after
   that timestamp. It accepts one exact marker authored by the dispatch actor:

   ```text
   SSE_RECOVERY_FIRE run_id=<github-run-id> rendezvous_id=<uuid>
   ```

4. The operator starts the clean k6 recovery with the same repository, Issue, run ID,
   and rendezvous ID. After the initial valid SSE event and fixture preflight, k6 emits
   `SSE_RECOVERY_READY`. The operator then posts the exact fire marker.
5. The prepared workflow chooses a fixed near-future epoch, submits the reviewed SSM
   script with that epoch, and waits until the invocation is `InProgress`. Only then
   does `github-actions[bot]` post:

   ```text
   SSE_RECOVERY_ARMED run_id=<github-run-id> rendezvous_id=<uuid> execute_at_epoch=<epoch>
   ```

6. k6 polls the public GitHub Issue comments API without an Authorization header. It
   accepts only the exact marker for the configured repository, Issue, run ID and UUID,
   authored by `github-actions[bot]`, with a bounded future epoch.
7. The remote script waits until the shared epoch, stops Valkey, preserves the fixed
   ten-second interruption, and performs its existing automatic recovery. k6 performs
   the approved mutation at a fixed offset inside that interruption window.
8. k6 requires the second valid SSE event, verifies the MySQL-backed owner HTTP state,
   and performs its existing cancellation cleanup. The workflow separately requires
   successful SSM completion and healthy Valkey recovery.

## Time Bounds

- The workflow fire-marker wait is bounded and causes no SSM submission on timeout.
- The scheduled epoch is fixed by reviewed code, not supplied by the operator.
- The remote script accepts only a numeric epoch within a small future window and
  rejects late or excessively future values before stopping Valkey.
- k6 accepts only a future epoch inside its own bounded rendezvous window.
- The mutation offset is fixed by reviewed code and leaves recovery time inside the
  existing ten-second interruption.
- The SSE request timeout covers the bounded marker wait, schedule window and recovery
  verification window, but remains finite.

Exact candidate seconds remain test constants until the contract tests and the next
approved staging run provide evidence. They are not production operating values.

## Failure and Recovery

- Missing, malformed, stale, wrong-actor or wrong-scope fire markers fail before SSM.
- Missing, malformed, stale, wrong-author or wrong-scope armed markers fail before the
  business mutation, so the fresh waiting fixture is not consumed.
- SSM submission or arming failure produces no armed marker. If the remote command has
  started, its existing exit trap restores Valkey.
- Workflow cancellation or timeout after SSM submission keeps the existing automatic
  recovery path, and the operator may run the fixed `recover-valkey` action.
- No result from this change alone closes #357. A fresh approved staging recovery run
  must prove interruption overlap, corrected SSE event, HTTP/MySQL convergence,
  cleanup, rollback and credential-literal absence.

## Security and Data Handling

- k6 receives no GitHub token, AWS credential or additional synthetic credential.
- The GitHub API host is fixed; repository, Issue, run ID and rendezvous ID are strictly
  validated and used only to select an exact marker.
- The workflow binds the fire marker to the dispatch actor and to a timestamp after the
  workflow became ready.
- The armed marker is accepted only from `github-actions[bot]`.
- The rendezvous UUID and epoch are coordination identifiers, not secrets. They are not
  included in k6 summaries or application logs.
- Account, token, cookie, cursor, waiting team ID, fixture contents and idempotency keys
  remain outside Issue comments and workflow logs.
- No arbitrary duration, shell command, host, environment key or production target is
  introduced.

## Interface Changes

The staging control workflow adds interrupt-only Issue and rendezvous inputs. Recover
and deployment actions reject those inputs. The reusable Valkey workflow receives the
validated scope, performs the bounded marker wait, schedules the fixed script and posts
the armed marker.

The recovery k6 configuration adds an explicitly approved rendezvous mode with fixed
GitHub API host and validated repository/Issue/run/UUID inputs. Local recovery may keep
the existing fixed-delay path; staging Issue-comment rendezvous and fixed delay are
mutually exclusive.

The Valkey script adds an optional scheduled epoch only for `interrupt`. `recover`
rejects it. The existing Compose preflight, image binding, fixed service, ten-second
interruption, exit-trap recovery and safe diagnostics remain unchanged.

## Verification

- Workflow contract tests cover permission scope, caller and deployment validation,
  OIDC-before-wait ordering, exact actor/time/scope binding, SSM `InProgress` before
  armed acknowledgement, and rejection of arbitrary inputs.
- Shell tests cover malformed, late and excessively future epochs failing before
  `docker compose stop`, plus the scheduled fixed interruption and recovery path.
- k6 RED/GREEN contract tests cover marker parsing, repository/Issue/run/UUID/author
  binding, bounded epoch validation, timeout-before-mutation, mutation ordering and
  cleanup preservation.
- The complete staging-control, Valkey and SSE k6 contract suites, shell syntax,
  allowlist diff and credential-literal scan are run before completion claims.
- Actual staging execution remains a separate, explicitly approved verification after
  merge using a fresh `WAITING` fixture.

## Rejected Alternatives

### Increase only the arm and stream timeouts

This preserves GitHub runner scheduling variance and cannot prove overlap. It has
already produced excluded staging evidence.

### Move fixture and synthetic credentials into GitHub Environment secrets

Running k6 and SSM in one job could provide direct coordination, but it introduces a
new secret distribution and fixture-management surface. The selected design keeps the
existing local synthetic-data boundary.

### Execute a second arbitrary remote command from the operator

Granting direct SSM or arbitrary shell access broadens authority beyond the fixed
staging control contract and is not required for this test.
