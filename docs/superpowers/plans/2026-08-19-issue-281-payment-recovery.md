# Issue #281 Payment Recovery Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the Platform Operator payment/refund recovery case workflow on top of PR #468's public Payment recovery contract without accessing Payment persistence or resending an external result that is already achieved or unknown.

**Architecture:** The branch is stacked on PR #468 Head `fc15ce65` because that approved prerequisite cannot yet merge while deployment load testing changes the Docker image. Platform Operator owns case, proposal, approval, execution lease, HTTP, masking, and immutable operational audit; workers invoke only scalar DTOs on `PaymentService`. A stable execution key and expected Payment versions fence every monetary command, while unknown results enter GET-only verification and never resend cancellation.

**Tech Stack:** Java 21, Spring Boot, Spring MVC/Security, Spring Data JPA, MySQL 8, Flyway, JUnit 5, Testcontainers, OpenAPI 3.1, AssertJ, Mockito

**Spec:** `docs/specs/payment-recovery/spec.md`

## Global Constraints

- Base exactly: PR #468 Head `fc15ce6568321dd8acc33b7275b2e6c5a3c41995`; rebase onto `origin/dev` only after #468 merges.
- Selected migration: `V64__create_payment_recovery_workflow.sql`; recheck all open PRs before commit and merge.
- Only a Payment-created handoff may create a case; operators cannot type payment/provider identifiers to create one.
- Platform Operator may import only `PaymentService` and `PaymentRecoveryContracts`/`PaymentContracts` scalar DTOs; Payment Entity, Repository, provider adapter, and transaction service imports are forbidden.
- Exact amounts are visible only to authorized operators and immutable audit. Provider reference stays masked; card, account, contact, raw provider payload, headers, tokens, and secrets never cross the boundary.
- A cumulative lineage amount up to KRW 200,000 and not above the original payment may self-approve; KRW 200,001 or more requires a different `SUPER_ADMIN` with `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE`.
- Compensation above the original payment remains `NOT_SUPPORTED` because Payment has no canonical payout mechanism; Admin approval alone cannot create one.
- Every mutation requires `Idempotency-Key`, expected versions, active assignment, current authority, and purpose-bound reauthentication.
- Approval only queues a durable execution; no operator-facing execute endpoint exists.
- Unknown/timeout results are never resent and move to bounded exact-result verification; exhaustion moves the case to `HOLD`.
- Local verification is targeted. GitHub CI remains the authoritative full regression suite.

---

### Task 1: Activate the stacked contract, OpenAPI, migration, and allowlist

**Files:**
- Modify: `docs/specs/payment-recovery/spec.md`
- Create: `docs/specs/payment-recovery/openapi.yaml`
- Modify: `docs/specs/platform-operator-openapi.yaml`
- Create: `backend/src/main/resources/db/migration/V64__create_payment_recovery_workflow.sql`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/PaymentRecoveryMigrationIT.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/PaymentRecoveryOpenApiContractTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`

**Interfaces:**
- Consumes: PR #468 public Payment recovery contract and the existing Platform Operator bearer/reauthentication headers.
- Produces: `/api/v1/platform-operators/payment-recovery-cases` routes and four immutable workflow tables.

- [ ] **Step 1: Update the active spec metadata**

Record stacked base `fc15ce65`, selected V64, the reason #468 is temporarily unmerged, and the rule that the branch must be rebased to post-#468 `origin/dev` before final merge.

- [ ] **Step 2: Write failing migration and OpenAPI tests**

The migration test must apply the real MySQL migration chain and assert tables, lifecycle CHECK constraints, requester/approver foreign keys, unique proposal/approval/execution identities, immutable proposal/approval triggers, execution lease fields, and expanded audit action/reason CHECKs. The OpenAPI tests must require list/detail/assignment/requery/proposal/approval/closure routes and reject provider ID/card/raw payload fields.

- [ ] **Step 3: Verify RED**

Run:

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.platformoperator.paymentrecovery.PaymentRecoveryMigrationIT
.\gradlew.bat test --tests com.miriyum.domain.platformoperator.paymentrecovery.PaymentRecoveryOpenApiContractTest --tests com.miriyum.domain.platformoperator.PlatformOperatorOpenApiContractTest
```

Expected: missing V64 tables and missing payment-recovery OpenAPI paths.

- [ ] **Step 4: Add V64 and OpenAPI**

V64 creates `payment_recovery_cases`, `payment_recovery_proposals`, `payment_recovery_approvals`, and `payment_recovery_executions`; adds no Payment foreign key and stores only the public handoff ID. Proposal and approval tables receive no UPDATE/DELETE application path and have MySQL no-update/no-delete triggers. Extend `platform_operator_audit_events` action/reason CHECKs for recovery actions while reusing `before_snapshot`/`after_snapshot`.

- [ ] **Step 5: Verify GREEN and commit**

Run the two commands from Step 3, then commit:

```powershell
git add docs/specs/payment-recovery/spec.md docs/specs/payment-recovery/openapi.yaml docs/specs/platform-operator-openapi.yaml backend/src/main/resources/db/migration/V64__create_payment_recovery_workflow.sql backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/PaymentRecoveryMigrationIT.java backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/PaymentRecoveryOpenApiContractTest.java backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java
git commit -m "feat(admin): 결제 복구 계약과 스키마 활성화"
```

### Task 2: Implement the case aggregate and intake convergence

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryEnums.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryCase.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/repository/PaymentRecoveryCaseRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryIntakeService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryIntakeJob.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryCaseTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryIntakeServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryIntakeConcurrencyIT.java`

**Interfaces:**
- Consumes: `PaymentService.claimManualRecoveryHandoffs(ClaimManualRecoveryHandoffsCommand)` and `acknowledgeManualRecoveryHandoff(AcknowledgeManualRecoveryHandoffCommand)`.
- Produces: `PaymentRecoveryCase` with `publicId`, `handoffId`, lineage/sequence, status, case version, and a unique active lineage.

- [ ] **Step 1: Write aggregate RED tests**

Test exact transitions `RECONCILIATION_PENDING -> INVESTIGATING -> PROPOSED -> EXECUTING|ADDITIONAL_APPROVAL_PENDING`, `EXECUTING -> VERIFYING`, `VERIFYING -> COMPLETED|HOLD|FAILED`, `HOLD -> INVESTIGATING|FAILED_UNRESOLVED`, and `FAILED -> INVESTIGATING`. Each method accepts `expectedCaseVersion`; stale versions and invalid transitions must return the recovery state-conflict error.

- [ ] **Step 2: Write intake crash/race RED tests**

Two workers receiving the same handoff must create one case. A crash after Admin commit but before Payment acknowledgement must replay the same case ID and acknowledge it without duplication. `NOT_REQUIRED` handoffs never create a case.

- [ ] **Step 3: Verify RED**

Run:

```powershell
.\gradlew.bat test --tests com.miriyum.domain.platformoperator.paymentrecovery.entity.PaymentRecoveryCaseTest --tests com.miriyum.domain.platformoperator.paymentrecovery.service.PaymentRecoveryIntakeServiceTest
.\gradlew.bat integrationTest --tests com.miriyum.domain.platformoperator.paymentrecovery.service.PaymentRecoveryIntakeConcurrencyIT
```

- [ ] **Step 4: Implement minimal aggregate and intake**

`PaymentRecoveryIntakeJob.runOnce(owner, limit)` claims scalar handoffs, calls `PaymentRecoveryIntakeService.createOrReplay(claim)` in one Admin transaction, then acknowledges through `PaymentService`. A unique handoff ID makes crash replay converge; no operator/controller method creates cases.

- [ ] **Step 5: Verify GREEN and commit**

Run Step 3 and commit with `feat(admin): 결제 복구 사건 intake 추가`.

### Task 3: Implement immutable proposal, approval, and execution authorization

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryProposal.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryApproval.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryExecution.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/repository/PaymentRecoveryProposalRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/repository/PaymentRecoveryApprovalRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/repository/PaymentRecoveryExecutionRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/exception/PaymentRecoveryErrorCode.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryRequestFingerprint.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryProposalTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryExecutionTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryApprovalConcurrencyIT.java`

**Interfaces:**
- Produces: immutable proposal versions, approval tier `SINGLE_OPERATOR|ADDITIONAL_SUPER_ADMIN`, unique actor approval, and stable UUID execution key.
- Consumes: literal Payment preview amounts/versions; no request-supplied free-form amount.

- [ ] **Step 1: Write RED tests for threshold and separation**

Literal cases: KRW 200,000 and total not above original is single-operator; KRW 200,001 is additional approval. Same actor cannot fill requester and high-value approver. Approver must contain `SUPER_ADMIN` and `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE`. Compensation action is rejected `NOT_SUPPORTED` before approval.

- [ ] **Step 2: Write MySQL race RED test**

Concurrent approvals for one proposal may create exactly one effective approval/execution. Replaying the same idempotency key returns the same execution; a different payload with the key returns the canonical idempotency conflict.

- [ ] **Step 3: Implement immutable records and constraints**

Store requester/approver authority versions and permission/role JSON snapshots. `PaymentRecoveryExecution` exposes `claim(owner, now, leaseUntil)`, `reclaimExpired(...)`, `markVerifying`, `markSucceeded`, `markFailed`, and `scheduleLookup`; every completion method verifies lease owner/token.

- [ ] **Step 4: Verify and commit**

Run the three task test classes and commit with `feat(admin): 결제 복구 승인과 실행 권한 추가`.

### Task 4: Add assignment, reauthentication, and audited command orchestration

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/dto/PaymentRecoveryRequests.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/dto/PaymentRecoveryResponses.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryAuditSnapshots.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryCommandService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriter.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorAuditEvent.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditAction.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditReason.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryCommandServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryAuditPrivacyTest.java`

**Interfaces:**
- Consumes: `AdminCaseAssignmentManager`, `AdminCaseAssignmentVerifier`, `HighRiskCommandGuard`, `OperatorAuthorityReader`, `IdempotencyExecutor`, and `PaymentService.previewManualRecoveryRefund`.
- Produces: assign, requery authorization, proposal, additional approval, and failed-unresolved closure commands.

- [ ] **Step 1: Write command RED tests**

Test missing assignment, wrong permission, stale case/Payment/proposal version, consumed/wrong-purpose reauthentication, duplicate submission, actor separation, suspended/reversioned authority, unsupported compensation, and no execution before required approval.

- [ ] **Step 2: Write privacy/audit RED tests**

Assert response and snapshot keys are an allowlist containing case/proposal/execution IDs, exact amount, masked provider reference, versions, action/tier/status, actors, idempotency key, and correlation ID. Serialize requests, responses, exceptions, and audit snapshots and assert absence of `card`, `account`, `providerPayload`, raw provider ID, password, approval token, authorization header, and secret.

- [ ] **Step 3: Implement orchestration**

`assign` creates a version-bound 30-minute `AdminCaseAssignment`. `requestRequery` consumes `PAYMENT_RECOVERY` reauthentication and queues `REQUERY_PROVIDER_RESULT`. `proposeRefund` calls Payment preview, stores its versions and fingerprint, and either self-approves/queues or waits for a different superadmin. `approve` consumes proposal-bound reauthentication. `closeUnresolved` is allowed only from `HOLD` and requires a different superadmin.

- [ ] **Step 4: Add recovery-specific immutable audit writer**

Add `appendRecovery(RecoveryEvent)` and a `PlatformOperatorAuditEvent.createRecovery(...)` factory that sets common case fields plus allowlisted before/after JSON snapshots. Do not add update/delete methods.

- [ ] **Step 5: Verify and commit**

Run the two task test classes and existing `PlatformOperatorAuditWriterTest`, then commit with `feat(admin): 결제 복구 명령 인가와 감사 추가`.

### Task 5: Implement leased execution and no-resend verification

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryExecutionTransaction.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryExecutionJob.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryExecutionJobTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryExecutionRuntimeIT.java`

**Interfaces:**
- Consumes: only `PaymentService.reconcileManualRecovery`, `requestManualRecoveryRefund`, and `inspectManualRecovery` with stored scalar versions and stable operation UUID.
- Produces: one claimed execution, bounded lookup scheduling, and terminal/hold case transitions.

- [ ] **Step 1: Write worker RED tests**

Test `ALREADY_APPLIED` completes by inspection without sending a new refund; timeout/UNKNOWN after refund schedules only requery; expired UNKNOWN lease reclaims the same lookup operation; explicit failure creates no automatic resend and requires a new proposal; stale Payment snapshot calls no provider command; authority/assignment recheck failure moves to `HOLD` before Payment call.

- [ ] **Step 2: Write real MySQL runtime RED tests**

Race two workers for one execution and assert one lease token wins. Simulate crash after Payment returns unknown and assert the next worker invokes only `reconcileManualRecovery`. Exhaust the bounded lookup count and assert one `HOLD` transition and immutable audit sequence.

- [ ] **Step 3: Implement transaction boundary and job**

The transaction service claims and validates durable authority; the job calls Payment outside the Admin transaction; result methods re-lock by execution ID and lease token. `RETRY_REFUND` changes permanently to lookup-only after UNKNOWN, so lease reclaim cannot infer/send the original command.

- [ ] **Step 4: Verify and commit**

Run both task test classes and commit with `feat(admin): 결제 복구 멱등 실행과 대사 재개 추가`.

### Task 6: Expose authorized HTTP queries and commands

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryQueryService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/controller/PaymentRecoveryController.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/controller/PaymentRecoveryControllerTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/controller/PaymentRecoveryHttpIT.java`

**Interfaces:**
- Produces: list/detail, self-assignment, exact requery request, proposal creation, additional approval, and held-case closure HTTP routes.
- Consumes: Platform Operator principal, `Idempotency-Key`, `X-Admin-Reauthentication`, expected versions in request bodies, and `X-Correlation-Id`.

- [ ] **Step 1: Write HTTP RED tests**

Test disabled feature 404, consumer/store JWT rejection, platform-operator authentication, permission denial, assignment denial, reauthentication denial, 409 version/idempotency conflict, 404 unknown case, response masking, and successful status codes/body envelopes.

- [ ] **Step 2: Implement controller/query service**

Queries require current `PAYMENT_RECOVERY_EXECUTE`; detail additionally verifies current version-bound assignment. Mutations delegate to command service and never call Payment directly from the controller.

- [ ] **Step 3: Verify and commit**

Run controller unit/HTTP tests plus both OpenAPI tests and commit with `feat(admin): 결제 복구 운영자 API 추가`.

### Task 7: Final targeted verification and delivery

**Files:**
- Modify only if verification exposes a scoped defect: files already listed in Tasks 1-6.

**Interfaces:**
- Produces: a clean stacked feature branch and Draft PR that clearly declares #468 as prerequisite.

- [ ] **Step 1: Recheck collision state**

Fetch `origin/dev`, inspect all open PR migration/OpenAPI files, and confirm V64 and `docs/specs/platform-operator-openapi.yaml` remain unclaimed outside #468/#281. If not, stop and update issue #281 before renumbering/changing allowlist.

- [ ] **Step 2: Run targeted unit verification**

Run only payment-recovery unit/controller/OpenAPI classes and directly changed shared audit tests.

- [ ] **Step 3: Run targeted real MySQL verification**

Run `PaymentRecoveryMigrationIT`, `PaymentRecoveryIntakeConcurrencyIT`, `PaymentRecoveryApprovalConcurrencyIT`, `PaymentRecoveryExecutionRuntimeIT`, and `PaymentRecoveryHttpIT`. Do not run the full backend suite locally.

- [ ] **Step 4: Verify diff boundaries**

```powershell
git diff --check
git diff --name-only fc15ce65...HEAD
rg -n "com\.miriyum\.domain\.payment\.(entity|repository|port)|PaymentTransactionService|PaymentRecoveryTransactionService" backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery
```

Expected: no whitespace errors, every changed file is allowlisted, and the forbidden-boundary search returns no matches.

- [ ] **Step 5: Commit, push, and open Draft PR**

Push `feature/281-payment-recovery`; open a Draft PR that targets `dev` but states `blocked by #468` and that its meaningful diff must be reviewed as `#468...#281` until #468 merges. Do not merge or push to `dev`.

## Exact File Allowlist

Only the following files may change. Any addition requires a deliberate #281 issue comment update before editing.

```text
docs/specs/payment-recovery/spec.md
docs/specs/payment-recovery/openapi.yaml
docs/specs/platform-operator-openapi.yaml
docs/superpowers/plans/2026-08-19-issue-281-payment-recovery.md
backend/src/main/resources/db/migration/V64__create_payment_recovery_workflow.sql
backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditAction.java
backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditReason.java
backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorAuditEvent.java
backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriter.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/controller/PaymentRecoveryController.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/dto/PaymentRecoveryRequests.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/dto/PaymentRecoveryResponses.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryEnums.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryCase.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryProposal.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryApproval.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryExecution.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/exception/PaymentRecoveryErrorCode.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/repository/PaymentRecoveryCaseRepository.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/repository/PaymentRecoveryProposalRepository.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/repository/PaymentRecoveryApprovalRepository.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/repository/PaymentRecoveryExecutionRepository.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryAuditSnapshots.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryRequestFingerprint.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryIntakeService.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryIntakeJob.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryCommandService.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryExecutionTransaction.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryExecutionJob.java
backend/src/main/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryQueryService.java
backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/PaymentRecoveryMigrationIT.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/PaymentRecoveryOpenApiContractTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/controller/PaymentRecoveryControllerTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/controller/PaymentRecoveryHttpIT.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryCaseTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryProposalTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/entity/PaymentRecoveryExecutionTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryAuditPrivacyTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryCommandServiceTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryIntakeServiceTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryIntakeConcurrencyIT.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryApprovalConcurrencyIT.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryExecutionJobTest.java
backend/src/test/java/com/miriyum/domain/platformoperator/paymentrecovery/service/PaymentRecoveryExecutionRuntimeIT.java
backend/src/test/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriterTest.java
```
