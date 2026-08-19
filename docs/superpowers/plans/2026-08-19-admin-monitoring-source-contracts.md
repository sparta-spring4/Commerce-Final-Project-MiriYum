# Admin Monitoring Source Contracts Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add source-owned, time-bounded public monitoring DTO/services for Reservation, MenuHold, Payment, and Waiting, including a truthful MenuHold status ledger, without introducing an admin snapshot.

**Architecture:** Each source domain owns its repositories and exposes only an immutable monitoring contract plus query service. Every query receives the same caller-supplied `asOf`; results carry `dataThrough`, raw `statusVersion`, completeness, reconciliation, and optional `historyAvailableFrom`. The later platform runtime depends only on these public services/DTOs.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Flyway, PostgreSQL, JUnit 5, Mockito, AssertJ, Testcontainers, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-19-admin-monitoring-design.md`

## Global Constraints

- Do not start until PR #448 is merged into `dev`. Fetch `origin/dev`, rebase this isolated branch, confirm V60 and V61 exist, and confirm V62 remains free.
- Recheck PR #459 before touching Payment or `ReservationDepositProcessRepository`; resolve contract changes against its merged form, never by copying a stale implementation.
- Do not reference another source domain's Entity or Repository from a public monitoring query service. Cross-source correlation is expressed as string IDs in DTOs.
- No admin-owned snapshot table, polling projection, or migration. V62 is source-owned MenuHold history only.
- Keep `MenuHoldTerminalService` pure. Write transition audits in the transactional runtime services that persist the state change.
- Existing MenuHold rows receive one `BASELINE` event at migration time. Earlier history is `UNAVAILABLE`; never synthesize transitions.
- Use the exact issue #280 allowlist. If a necessary path is absent, amend the issue before editing it.
- Run only named unit tests and affected integration classes. Never run local `build`, `check`, full `integrationTest`, or all integration shards.

## Public Contract Shape

Each `*MonitoringContracts` class contains source-specific enums and these semantically equivalent records (source status types differ):

```java
public record Seek(Instant statusChangedAt, String caseId) {}
public record ChangeQuery(Instant asOf, Instant changedFrom, Instant changedTo,
                          Long storeId, Set<Status> statuses, Seek after, int limit) {}
public record CaseReference(String caseId, long storeId, Instant statusChangedAt) {}
public record ReferencePage(List<CaseReference> items, Instant asOf, Instant dataThrough) {}
public record BatchQuery(Instant asOf, List<String> caseIds) {}
public record SourceCell(String caseId, long storeId, Status status, long statusVersion,
                         Instant statusChangedAt, Instant asOf, Instant dataThrough,
                         Completeness completeness, ReconciliationStatus reconciliationStatus,
                         Instant historyAvailableFrom, Links links) {}
public record BatchResult(Map<String, SourceCell> cases, Instant asOf, Instant dataThrough) {}
public record DetailQuery(Instant asOf, String caseId) {}
public record Transition(long resultVersion, Status before, Status after, Instant occurredAt) {}
public record Detail(SourceCell cell, List<Transition> history, MaskedSubject subject) {}
```

All constructors reject missing `dataThrough`, `dataThrough > asOf`, negative versions, blank IDs, duplicate case IDs, and unsorted history. Public service signatures are:

```java
ReferencePage findChangedCases(ChangeQuery query);
BatchResult findCases(BatchQuery query);
Optional<Detail> findCase(DetailQuery query);
```

`Completeness` is `COMPLETE`, `DELAYED`, `PARTIAL`, or `UNAVAILABLE`; `ReconciliationStatus` is `MATCHED`, `REQUIRED`, or `UNKNOWN`. IDs exposed in JSON-facing records remain strings.

---

### Task 1: Rebase gate and activate the contract documentation

**Files:**
- Create: `docs/specs/admin-monitoring/spec.md`
- Create: `docs/specs/admin-monitoring/openapi.yaml`
- Modify: `docs/specs/platform-operator-openapi.yaml`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/AdminMonitoringOpenApiContractTest.java`

- [ ] Verify #448 is merged, #459 state, V62 availability, clean worktree, and `git merge-base --is-ancestor origin/dev HEAD`; stop and report if any gate fails.
- [ ] Write `AdminMonitoringOpenApiContractTest` asserting the aggregate references `/api/v1/platform-operators/admin-monitoring/cases`, the list/detail schemas include `asOf`, `dataThrough`, per-source completeness and failures, and HTTP 200/400/401/403/404/503 contracts.
- [ ] Run `./gradlew test --tests com.miriyum.docs.AdminMonitoringOpenApiContractTest` and confirm RED because the paths/schemas do not exist.
- [ ] Add the approved case model, fixed ordering, 31-day filter bound, opaque cursor, masking, assignment, and partial-failure rules to the spec and OpenAPI; reference it from the platform aggregate.
- [ ] Re-run the named test and confirm GREEN.
- [ ] Commit: `docs(admin-monitoring): activate source contract specification`.

### Task 2: Add the MenuHold V62 status ledger

**Files:**
- Create: `backend/src/main/resources/db/migration/V62__add_menu_hold_monitoring_ledger.sql`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/entity/MenuHold.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/entity/MenuHoldTransitionAudit.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/repository/MenuHoldTransitionAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/TemporaryMenuHoldServiceRuntime.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuHoldServiceRuntime.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/MenuHoldMigrationContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/entity/MenuHoldTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/service/TemporaryMenuHoldServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuHoldServiceTest.java`

- [ ] Write entity and repository tests for positive monotonic `resultVersion`, immutable `BASELINE|CREATED|TRANSITION`, stable reservation/hold link snapshots, and ordering by `(menuHoldId, resultVersion)`.
- [ ] Add runtime tests proving creation writes exactly one `CREATED` v0 event, each persisted status transition writes one `TRANSITION` event in the same transaction, and an idempotent replay writes no second event.
- [ ] Run only the four named tests and confirm RED.
- [ ] Add `menu_holds.status_version BIGINT NOT NULL DEFAULT 0`; add the append-only audit table, unique `(menu_hold_id,result_version)`, lookup indexes, and exactly one migration-time `BASELINE` event per existing row.
- [ ] Add `@Version long statusVersion` to `MenuHold`, implement the audit entity/repository, and persist audit events beside successful runtime writes. Do not modify pure transition policy code.
- [ ] Re-run the four named tests and affected existing `MenuHoldTest`; confirm GREEN.
- [ ] Commit: `feat(menu-hold): record truthful status transition ledger`.

### Task 3: Publish the MenuHold monitoring contract

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/dto/MenuHoldMonitoringContracts.java`
- Create: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuHoldMonitoringQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/repository/MenuHoldRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/repository/MenuHoldTransitionAuditRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/menuhold/contract/MenuHoldMonitoringPublicContractTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/menuhold/service/MenuHoldMonitoringQueryServiceTest.java`

- [ ] Write contract validation tests and an integration test covering changed references, bounded batch reads, detail history, BASELINE `historyAvailableFrom`, as-of reconstruction, and stable `reservation-hold:{id}` linkage.
- [ ] Run both named tests and confirm RED.
- [ ] Implement repository projections inside MenuHold and the three public service methods. For pre-baseline `asOf`, return `UNAVAILABLE`; for current rows behind `asOf`, return `DELAYED`, never `COMPLETE` by assumption.
- [ ] Re-run both tests and confirm GREEN.
- [ ] Commit: `feat(menu-hold): expose public monitoring query contract`.

### Task 4: Publish the Reservation monitoring contract

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/dto/contract/ReservationMonitoringContracts.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationMonitoringQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationHoldRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationHoldTransitionAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCheckInAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationFulfillmentAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationNoShowAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationDepositProcessRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/contract/ReservationMonitoringPublicContractTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationMonitoringQueryServiceTest.java`

- [ ] Write tests for `reservation-hold:{holdId}` stability after final reservation creation, direct `reservation:{reservationId}`, confirmed version 0, one terminal transition version 1, check-in/no-show/fulfillment history, and masked contact data.
- [ ] Run both named tests and confirm RED.
- [ ] Add source-owned projection queries and implement the public service. `ReservationDepositProcess` is correlation only and must not appear as a state ledger.
- [ ] Re-run both tests and confirm GREEN.
- [ ] Commit: `feat(reservation): expose public monitoring query contract`.

### Task 5: Publish the Payment monitoring contract

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/payment/dto/PaymentMonitoringContracts.java`
- Create: `backend/src/main/java/com/miriyum/domain/payment/service/PaymentMonitoringQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/payment/repository/PaymentRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/payment/repository/PaymentLedgerEntryRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/payment/repository/PaymentRefundRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/payment/contract/PaymentMonitoringPublicContractTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/payment/service/PaymentMonitoringQueryServiceTest.java`

- [ ] Rebase/check #459's merged Payment contract and repository changes, then write tests for changed references, batch/detail, raw optimistic version, ledger `occurredAt`, refund reconciliation, and masked payment reference.
- [ ] Run both named tests and confirm RED.
- [ ] Implement the separate contract/query service and source projections; do not extend `PaymentContracts` or expose Payment entities.
- [ ] Re-run both tests and confirm GREEN.
- [ ] Commit: `feat(payment): expose public monitoring query contract`.

### Task 6: Publish the Waiting monitoring contract

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingMonitoringContracts.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingMonitoringQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingTeamRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingTransitionAuditRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/contract/WaitingMonitoringPublicContractTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingMonitoringQueryServiceTest.java`

- [ ] Write tests for `waiting:{waitingTeamId}`, raw version, as-of transition reconstruction, conversion correlation, data-through, masked subject, and deterministic seek ordering.
- [ ] Run both named tests and confirm RED.
- [ ] Implement source projections and the public service against the post-#448 schema.
- [ ] Re-run both tests and confirm GREEN.
- [ ] Commit: `feat(waiting): expose public monitoring query contract`.

### Task 7: Contract boundary and publication

**Files:** No additional files; this task verifies the allowlisted contract and service tests created above.

- [ ] Run all four DTO contract tests, four query service tests, MenuHold ledger tests, and OpenAPI contract test; record exact commands/results. Do not broaden the suite.
- [ ] Run `git diff --check`, compare `git diff --name-only origin/dev...HEAD` with issue #280's source allowlist, and amend the issue before any discrepancy is committed.
- [ ] Commit: `test(admin-monitoring): verify public source boundaries`.
- [ ] Push only `feature/280-admin-monitoring-contracts` and open a draft PR to `dev`, explicitly marking the runtime PR blocked until merge and CI success.

## Completion Gate

Do not begin the runtime plan until this source-contract PR is merged to `dev`, GitHub CI is green, and the merge commit is present in a fresh `origin/dev` fetch.
