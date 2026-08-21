# Payment manual recovery active specification

- Status: approved in brainstorming on 2026-08-19
- Prerequisite issue: #457
- Consumer issue: #281
- Stacked base: PR #468 Head `fc15ce6568321dd8acc33b7275b2e6c5a3c41995`
- Prerequisite branch: `feature/457-payment-recovery-contract`
- Consumer branch: `feature/281-payment-recovery`
- Selected consumer migration: `V66__create_payment_recovery_workflow.sql`
- Active HTTP contract: `docs/specs/payment-recovery/openapi.yaml`

Issue #281 started as a stacked branch because approved prerequisite PR #468 could not merge while deployment load testing changed the Docker image. PR #468 is now included in `dev`. PR #475, which owns V64 and V65, merged to `dev` at `69d29b81`; #281 owns V66 and has merged that latest `dev`. The shared Platform Operator OpenAPI conflict was resolved by preserving both admin-monitoring and payment-recovery path sets, and the collision checks are repeated immediately before merge.

## 1. Purpose

Provide a safe manual workflow for payment and refund incidents that automatic reconciliation could not resolve. The design preserves Payment as the sole owner of provider commands and monetary state while Platform Operator owns incident assignment, proposal, approval, execution scheduling, and immutable operational audit.

The work is deliberately split into two pull requests. Issue #457 first establishes the minimal Payment-owned public recovery contract and the automatic-reconciliation handoff. After that PR is merged to `dev`, issue #281 consumes only that contract to implement the Admin workflow.

## 2. Preconditions and governing contracts

Issues #236 through #242, #275, and #276 are closed on the approved base. The base contains:

- Payment's canonical `PaymentService`, public DTOs, provider reconciliation, refund idempotency, and reconciliation-required behavior;
- Platform Operator RBAC, reauthentication, high-risk command guard, case assignment, and immutable audit foundations;
- `PAYMENT_RECOVERY_EXECUTE` and `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE` permissions;
- `PAYMENT_RECOVERY` case, command-purpose, and target types;
- Reservation deposit disposition and refund jobs with bounded automatic attempts.

Repository ownership rules remain authoritative: another domain may call only Payment's public Service/DTO contract and must not import Payment entities, repositories, transaction services, or provider clients.

## 3. Safety invariants

1. Only a system-owned automatic workflow may create a manual-recovery handoff, and only after bounded automatic reconciliation reaches `RECOVERY_REQUIRED`.
2. An operator cannot create a recovery case by typing a payment or provider identifier.
3. A provider command with an unknown result is never sent again. Only exact provider-result lookup may follow.
4. An already achieved provider result is not repeated; Payment converges its canonical state and ledger instead.
5. Platform Operator never directly edits a payment, refund, ledger row, amount, recipient, or provider result.
6. All provider requery, refund validation, refund identity selection, idempotency, and ledger convergence occur inside Payment.
7. Compensation execution remains `NOT_SUPPORTED` until Payment owns a canonical payout mechanism.
8. Exact monetary amounts are available to authorized operators and immutable audit. Provider external IDs are masked. Card, account, contact, provider raw payload, credentials, and secrets never enter responses, logs, or audit.
9. Case, proposal, approval, and execution transitions are conditional and versioned. Duplicate approval and execution are rejected by both application rules and database constraints.

## 4. Two-phase architecture

### 4.1 Issue #457: Payment-owned prerequisite

Reservation calls a new public Payment handoff command only when its own bounded automatic job has exhausted retries and marked the source obligation `RECOVERY_REQUIRED`. Payment records that handoff idempotently and exposes a minimal scalar recovery contract through `PaymentService`.

The contract provides four capabilities:

- inspect a handoff: current Payment version, unresolved category, original amount, refunded amount, remaining refundable amount, and allowed actions;
- reconcile an external result: reuse Payment's exact PortOne lookup and marker validation, then converge the existing aggregate and append the existing canonical ledger event;
- preview a refund: let Payment calculate refund identity, cumulative refund lineage, and allowable amount; Platform Operator applies the ADMIN-006 approval threshold;
- request a refund: reuse Payment's existing refund claim, refund ID, fingerprint, and idempotency behavior.

The contract does not provide a generic ledger-correction command. It does not expose Payment persistence objects. It does not add a payout or compensation path.

The handoff is claimed and acknowledged with retry-safe semantics so a crash between Payment handoff discovery and Admin case creation converges to one case. The source tuple and handoff key are unique.

### 4.2 Issue #281: Platform Operator consumer

Platform Operator owns:

- the central recovery case and its version;
- assignment through the existing `admin_case_assignments` service;
- immutable proposals and approvals;
- durable execution authorization and worker leasing;
- Admin HTTP and OpenAPI;
- masked operational views and immutable audit.

It calls only the merged `PaymentService` recovery methods and scalar DTOs from #457. It does not add fallback access to Payment internals.

## 5. Case state machine

The case states are:

```text
RECONCILIATION_PENDING
  -> INVESTIGATING
  -> PROPOSED
  -> EXECUTING                         (ordinary approval tier)
  -> ADDITIONAL_APPROVAL_PENDING       (high-value approval tier)
       -> EXECUTING

EXECUTING -> VERIFYING
VERIFYING -> COMPLETED | HOLD | FAILED
HOLD -> INVESTIGATING | FAILED_UNRESOLVED
FAILED -> INVESTIGATING                (new proposal version required)
```

`FAILED_UNRESOLVED` is terminal for that case and cannot be reopened or rewritten. Issue #281 does not let an operator create a successor case and does not infer lineage from masked Payment data. A linked successor is deferred until Payment publishes a scalar correlation from a newly eligible automatic handoff to the prior recovery lineage; without that public contract, new Payment evidence remains in automatic reconciliation and cannot create an Admin case.

Every transition checks the expected case version. Assignment is keyed by `PAYMENT_RECOVERY`, public case ID, and case version. A version change invalidates stale assignment and command attempts until the assignment is renewed for the new version.

The assignment command has three explicit state-dependent behaviors. `RECONCILIATION_PENDING` starts the first investigation and creates the assignment for the incremented case version. `HOLD` or `FAILED` resumes investigation and creates the assignment for the incremented version. `INVESTIGATING` does not advance the case version; it may renew or transfer only an expired assignment row for that exact version under a pessimistic lock. An active assignment cannot be taken over, and all other case states reject assignment as a state conflict.

## 6. Roles, reauthentication, and separation of duties

An assigned operator with `PAYMENT_RECOVERY_EXECUTE` may investigate and submit a proposal. Submission consumes reauthentication bound to payment recovery purpose, target, case ID, and case version.

For a cumulative lineage amount of at most KRW 200,000 and not above the original payment amount, the assigned operator may approve their own proposal and cause execution to be queued.

For KRW 200,001 or more, or for compensation above the original payment amount, approval requires:

- a different Platform Operator account from the requester;
- superadmin role;
- `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE`;
- separate reauthentication bound to the same proposal and case version.

The database records requester and approver independently and enforces uniqueness of an approval for a proposal and approver. Application and database rules reject the same actor in high-value tiers.

Before an external command, the worker rechecks current account status, authority version, permissions, assignment validity, case/proposal/execution version, and requester/approver separation. A failed recheck moves the case to `HOLD` without calling Payment.

No raw reauthentication token is persisted. Approval commits only durable execution authorization; the HTTP request does not perform the external monetary command.

## 7. Idempotent execution and provider outcomes

Approval atomically creates one pending execution with a stable unique execution key. A worker claims it with a bounded lease and token. A crashed lease may be reclaimed, but Payment's stable command identity remains unchanged.

Outcomes are handled as follows:

- `SUCCEEDED`: move to `VERIFYING`, inspect/requery through Payment, then `COMPLETED` after canonical state convergence;
- `ALREADY_APPLIED`: do not repeat the command; verify and complete through Payment;
- `UNKNOWN`, timeout, or response loss: do not resend; remain in `VERIFYING` and schedule exact provider lookup only;
- explicit provider failure: move to `FAILED`; another attempt requires a new immutable proposal version and approval, while Payment reuses the same refund identity and idempotency lineage;
- stale Payment snapshot or changed refundable amount: do not call the provider; move to `HOLD` or `INVESTIGATING` and require a new proposal;
- unsupported compensation: reject before approval with `NOT_SUPPORTED`.

After bounded provider-result lookups are exhausted, the case moves to `HOLD`. Manual closure to `FAILED_UNRESOLVED` is itself a high-risk action requiring a different superadmin and separate reauthentication. The underlying Payment/refund remains reconciliation-required and isolated.

## 8. Persistence model

Issue #457 owns a Payment-side handoff table with:

- opaque public handoff ID;
- source type, source ID, source version, and unique source fingerprint;
- Payment-owned references kept internal to Payment;
- unresolved category and status;
- claim owner, claim token, and lease expiry;
- acknowledgement and timestamps;
- optimistic row version.

Issue #281 owns:

- `payment_recovery_cases`: public case ID, handoff ID, lineage and sequence, status, case version, current proposal version, active-lineage marker, timestamps. The current intake opens each independently eligible Payment handoff as sequence 1; lineage and sequence columns reserve the future linked-successor contract without granting Admin access to Payment internals;
- `payment_recovery_proposals`: immutable proposal version, action, exact amount, Payment snapshot/version, fingerprint, approval tier, requester authority snapshot;
- `payment_recovery_approvals`: immutable approver, authority snapshot, approval tier and timestamp, with duplicate and separation constraints;
- `payment_recovery_executions`: stable execution key, status, lease owner/token/expiry, attempt metadata, masked outcome, timestamps, and row version.

Assignments reuse `admin_case_assignments`; no payment-recovery assignment table is created. Audit reuses immutable `platform_operator_audit_events` and its existing before/after JSON snapshots through a recovery-specific writer method; no mutable recovery audit table is introduced.

## 9. Admin HTTP contract

The #281 API provides only:

- list and detail recovery cases, including `caseVersion`, `handoffVersion`, `paymentVersion`, and `recoveryVersion` required by follow-up commands;
- expose the current, unexpired central assignment operator ID and whether it belongs to the authenticated operator in list and detail summaries, so only that operator receives the ordinary detail entry while other assignments are shown as in progress;
- list pending additional approvals only to a different `SUPER_ADMIN` with `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE`, and allow that eligible approver to read the exact case/proposal versions without inheriting the requester's assignment;
- request an exact provider-result requery;
- create a recovery proposal;
- approve a proposal when the additional tier is required;
- manually close a held case as `FAILED_UNRESOLVED`;
- existing common assignment operations.

There is no operator-facing execute endpoint. Approval queues the durable execution, and the worker executes it.

All mutating endpoints require an idempotency key and expected case/proposal version. Requery and proposal requests use the source versions returned by the public list, detail, or assignment response; clients do not infer or obtain those values from Payment internals. Conflicts return the repository's canonical concurrent-modification response. Payment recovery business failures map from the public Payment error contract rather than leaking provider errors.

## 10. Masking, logs, and audit

Authorized responses contain public recovery references, exact KRW amounts, statuses, versions, allowed actions, and masked provider reference only. Provider raw responses are reduced to an allowlisted scalar result inside Payment before crossing the domain boundary.

Audit snapshots contain exact requested/effective amount, action, tier, case/proposal/execution versions, actors and authority snapshots, masked external reference, before/after state, idempotency key, and correlation ID. They never contain card data, bank/account data, personal contact data, provider payloads, request headers, approval tokens, access tokens, or secrets.

Application logging uses public case/execution IDs and correlation IDs. Exceptions from provider adapters are mapped and sanitized before logging or crossing the Payment boundary.

## 11. OpenAPI, migrations, and exact allowlists

Migration numbers are assigned only after fetching the latest `origin/dev` and inspecting open PRs for each PR. PR #475 owns V64 and V65 and merged first at `69d29b81`. #281 is the later migration owner and uses V66, preserving Flyway deployment order.

The shared Admin OpenAPI and migration directory are checked:

1. immediately before implementation;
2. immediately before commit;
3. immediately before merge.

Before implementation, each issue is updated with its exact file allowlist, selected migration number, and active spec/OpenAPI paths. Changes outside that allowlist stop the work until the issue is updated deliberately.

#457 may change only Payment-owned public contract/runtime/test files, the minimal Reservation job/test files needed for handoff, its migration, and its design/spec documents. #281 may change only Platform Operator payment-recovery runtime/test files, required shared Platform Operator enums/audit writer, its migration, and payment-recovery/Admin OpenAPI files. #281 must not change Payment entities, repositories, provider adapters, or transaction services.

## 12. Test strategy

Local verification follows the repository policy and does not run the full backend suite.

Issue #457 local tests cover:

- Payment public recovery contract and error mapping;
- handoff eligibility and idempotency;
- Reservation handoff only after automatic exhaustion;
- exact provider lookup, already-achieved result, explicit failure, unknown result, and no resend;
- real MySQL handoff claim and refund-idempotency races;
- migration contract.

Issue #281 local tests cover:

- state machine, approval threshold, actor separation, masking, and worker behavior;
- HTTP authorization, reauthentication, idempotency, and optimistic-version conflicts;
- real MySQL concurrent approval, execution claim, immutable audit, and migration behavior;
- Payment public-contract boundary integration;
- payment-recovery and shared Admin OpenAPI route contracts.

GitHub CI is the authoritative full regression suite for each Draft PR. Until CI evidence exists, full regression remains pending rather than being replaced by a broad local run.

## 13. Delivery sequence

1. Update #457 with the active contract spec, selected migration, exact allowlist, and implementation plan.
2. Implement #457 test-first in `feature/457-payment-recovery-contract`, run targeted verification, commit, push, and open a Draft PR to `dev`.
3. User merges the prerequisite PR after review and CI.
4. Fetch the new `origin/dev`, confirm the public contract and collision state, and create a new isolated worktree on `feature/281-payment-recovery`.
5. Update #281 with the active spec/OpenAPI, new migration number, exact allowlist, and implementation plan.
6. Implement #281 test-first, run targeted verification, commit, push, and open a Draft PR to `dev`.
7. Recheck shared Admin OpenAPI and migration collision state immediately before merge and report it to the user. Never push directly to `dev`.
