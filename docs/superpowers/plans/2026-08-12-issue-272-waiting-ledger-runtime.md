# Issue #272 Waiting Ledger Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매장 운영자가 중앙 FIFO 웨이팅 원장을 조회하고 호출·도착·입장·취소·일괄 종결할 수 있으며, #271 설정 runtime이 활성 팀 영향과 종결 job을 호출할 수 있는 공개 Service를 제공한다.

**Architecture:** `com.miriyum.domain.reservation.waiting` capability가 원장 Entity·Repository·Service를 소유한다. 기존 Store 공개 Service와 DTO만 감싼 `WaitingStoreAuthorityPort` adapter로 권한을 확인하고, 기존 `IdempotencyExecutor`와 MySQL 행 잠금·unique constraint로 명령·순번 경합을 해결한다. 일반 사용자 HTTP 등록과 #271 설정 PUT 연결은 이 PR에서 만들지 않는다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, MySQL 8, Flyway V36, JUnit 5, Mockito, MockMvc, Testcontainers, OpenAPI 3.1.

## Global Constraints

- 브랜치와 PR의 주 Issue는 #272이며 대상 브랜치는 `dev`다.
- 새 최상위 도메인이나 `application`, `manager`, `impl` wrapper package를 만들지 않는다.
- Waiting은 Store Entity·Repository를 직접 참조하지 않고 기존 `StoreService` 공개 메서드와 공개 DTO만 사용한다.
- 공개 상태는 `WAITING`, `CALLED`, `ARRIVED`, `CHECKED_IN`, `CANCELLED`, `NO_SHOW`, `CLOSED_BY_STORE`, `RESERVATION_CONVERTING`이다.
- FIFO는 매장·KST 영업일별 단조 증가 sequence이며 취소·종결 번호를 재사용하지 않는다.
- 명령은 `Idempotency-Key`와 `expectedVersion`을 요구하고 GET에는 멱등 키를 요구하지 않는다.
- #271 설정 runtime, 일반 사용자 HTTP 등록, 알림 전달, SSE, 프론트엔드, 예약 전환, 결제는 제외한다.
- 제품 사실의 정본은 `docs/specs/waiting/`과 `docs/service-policies/05-waiting.md`이며 이 계획 문서는 정본이 아니다.

---

## 파일 구조

**정본·계약**

- Modify: `docs/specs/waiting/spec.md`
- Modify: `docs/specs/waiting/openapi.yaml`
- Modify: `docs/specs/store-operator-openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`

**Persistence**

- Create: `backend/src/main/resources/db/migration/V36__create_waiting_ledger.sql`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingTeam.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingQueueSequence.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingActiveMembership.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingTransitionAudit.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingStatusEvent.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingClosureJob.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingClosureJobItem.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingTeamStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingSource.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingActorType.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingClosureJobStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingClosureItemStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingTeamRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingQueueSequenceRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingActiveMembershipRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingTransitionAuditRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingStatusEventRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingClosureJobRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingClosureJobItemRepository.java`

**Service·HTTP**

- Modify: `backend/src/main/java/com/miriyum/domain/reservation/exception/ReservationErrorCode.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingStoreAuthorityPort.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/StoreServiceWaitingAuthorityAdapter.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerService.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingCommandFacade.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureService.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureJobRunner.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/controller/storeoperator/WaitingStoreOperatorController.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingTeamTransitionRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingTeamSearchRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingTeamSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingTeamPageResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingActiveTeamImpact.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingClosureCommandResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingClosureJobSnapshot.java`

**Tests**

- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/entity/WaitingTeamTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/repository/WaitingMigrationTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingCommandFacadeTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingCommandFacadeIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/controller/storeoperator/WaitingStoreOperatorControllerTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingLedgerConcurrencyIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/exception/ReservationErrorCodeTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/ReservationProductionDependencyTest.java`

---

### Task 1: Canonical Waiting ledger contract

**Files:** canonical contract and contract tests listed above.

**Interfaces:**

- Produces exact store-operator routes, DTO fields, errors `WAITING_003`~`WAITING_010`, state enum, and #272/#271 ownership boundary.
- Consumed by every later task; production code must not invent additional paths, states, fields, or error codes.

- [ ] **Step 1: Extend the OpenAPI contract test as RED**

Add exact assertions for these paths and methods:

```java
Map<String, Set<String>> LEDGER_OPERATIONS = Map.of(
    "/api/v1/store-operators/stores/{storeId}/waiting-teams", Set.of("get"),
    "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}", Set.of("get"),
    "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/call", Set.of("post"),
    "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/arrive", Set.of("post"),
    "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/check-in", Set.of("post"),
    "/api/v1/store-operators/stores/{storeId}/waiting-teams/{waitingTeamId}/cancel", Set.of("post"),
    "/api/v1/store-operators/stores/{storeId}/waiting-close-jobs/{jobId}", Set.of("get"));
```

Assert POST operations require bearer auth, `Idempotency-Key`, and `expectedVersion`; exact response sets include `200/400/401/403/404/409/429`; list schemas exclude phone, coordinate, and other consumer identifiers.

- [ ] **Step 2: Run RED contract tests**

Run:

```powershell
.\gradlew.bat test --tests "*WaitingOpenApiContractTest" --tests "*AudienceOpenApiContractTest" --rerun-tasks
```

Expected: FAIL because ledger paths and schemas do not exist.

- [ ] **Step 3: Add canonical spec and OpenAPI**

Document transition table, FIFO, cursor ordering `(queueSequence, waitingTeamId)`, privacy, closure job states, and exact errors. Add only post-MVP1 store-operator audience references; keep MVP1 aggregate unchanged.

- [ ] **Step 4: Run GREEN contract tests and lint**

Run the focused Gradle command again, then:

```powershell
npx --yes @redocly/cli@2.35.1 lint docs/specs/waiting/openapi.yaml
npx --yes @redocly/cli@2.35.1 bundle docs/specs/waiting/openapi.yaml --output .superpowers/sdd/waiting-ledger-bundle.yaml
```

Expected: all pass; generated bundle remains ignored.

- [ ] **Step 5: Commit**

```powershell
git add docs/specs/waiting docs/specs/store-operator-openapi.yaml backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingOpenApiContractTest.java backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java
git commit -m "docs(reservation): 웨이팅 원장 API 계약 정의"
```

### Task 2: V36 persistence and aggregate state machine

**Files:** all Persistence files and `WaitingTeamTest`, `WaitingMigrationTest`.

**Interfaces:**

- Produces `WaitingTeam.create(...)`, `call(...)`, `arrive(...)`, `checkIn(...)`, `cancel(...)`, `markNoShow(...)`, `closeByStore(...)`.
- Every transition accepts `expectedVersion` and `Instant occurredAt`; success increments version exactly once.
- Repositories expose `findByIdForUpdate`, FIFO head lookup, active count, sequence row lock, and closure item batch claims.

- [ ] **Step 1: Write RED state-machine tests**

Representative assertion:

```java
WaitingTeam team = WaitingTeam.create(10L, 20L, LocalDate.of(2026, 8, 12), 2,
        WaitingSource.REMOTE, 7L, Instant.parse("2026-08-12T03:00:00Z"));
team.call(0L, Instant.parse("2026-08-12T03:01:00Z"));
assertThat(team.getStatus()).isEqualTo(WaitingTeamStatus.CALLED);
assertThat(team.getArrivalDeadline()).isEqualTo(Instant.parse("2026-08-12T03:11:00Z"));
assertThat(team.getVersion()).isEqualTo(1L);
assertThatThrownBy(() -> team.checkIn(1L, Instant.parse("2026-08-12T03:02:00Z")))
        .isInstanceOf(ServiceException.class)
        .extracting(ex -> ((ServiceException) ex).getErrorCode())
        .isEqualTo(ReservationErrorCode.WAITING_INVALID_TRANSITION);
```

Cover every allowed transition, stale version, 10-minute boundary, terminal immutability, and reservation-converting being non-enterable.

- [ ] **Step 2: Write RED migration tests**

Verify V36 exact table set, FK targets, active membership unique key, store/date/sequence unique key, audit command unique key, closure job/item unique keys, status checks, and required indexes.

- [ ] **Step 3: Run RED tests**

```powershell
.\gradlew.bat test --tests "*WaitingTeamTest" --tests "*WaitingMigrationTest" --rerun-tasks
```

Expected: FAIL because V36 and production types are absent.

- [ ] **Step 4: Implement V36, enums, entities, and repositories**

Use `waiting_active_memberships(store_id, consumer_account_id)` as the active duplicate lock. `WaitingQueueSequence.allocate()` returns current `nextSequence` and increments it. Store timestamps as UTC-compatible `DATETIME(6)` and preserve `business_date` separately.

- [ ] **Step 5: Run GREEN tests and migration integration gate**

```powershell
.\gradlew.bat test --tests "*WaitingTeamTest" --tests "*WaitingMigrationTest" --rerun-tasks
.\gradlew.bat integrationTest --tests "*WaitingMigrationTest" --rerun-tasks
```

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/resources/db/migration/V36__create_waiting_ledger.sql backend/src/main/java/com/miriyum/domain/reservation/waiting/entity backend/src/main/java/com/miriyum/domain/reservation/waiting/repository backend/src/test/java/com/miriyum/domain/reservation/waiting/entity backend/src/test/java/com/miriyum/domain/reservation/waiting/repository backend/src/main/java/com/miriyum/domain/reservation/exception/ReservationErrorCode.java backend/src/test/java/com/miriyum/domain/reservation/exception/ReservationErrorCodeTest.java
git commit -m "feat(reservation): 웨이팅 원장 영속 모델 구현"
```

### Task 3: Authority, FIFO ledger service, and idempotent command facade

**Files:** authority port/adapter, ledger service, command facade, DTOs, service tests, dependency test.

**Interfaces:**

```java
public interface WaitingStoreAuthorityPort {
    WaitingStoreAuthority requireRead(long operatorAccountId, long storeId);
    WaitingStoreAuthority requireMutation(long operatorAccountId, long storeId);
}

public record WaitingStoreAuthority(long storeId, ZoneId timeZoneId) {}

public record WaitingActiveTeamImpact(long storeId, long activeTeamCount) {}
```

`requireRead` accepts APPROVED stores in OPEN, TEMPORARILY_CLOSED, CLOSED. `requireMutation` accepts APPROVED OPEN or TEMPORARILY_CLOSED only. The adapter calls existing Store public Service methods/DTO and contains no Store repository access.

- [ ] **Step 1: Write RED authority and ledger tests**

Verify account/ownership checks precede any Waiting lookup, other-store teams return privacy-safe `WAITING_003`, FIFO call rejects non-head with `WAITING_007`, stale version returns `WAITING_005`, and active impact counts only `WAITING/CALLED/ARRIVED`.

- [ ] **Step 2: Write RED facade fingerprint tests**

Assert canonical fingerprint includes method, route template, storeId, teamId and expectedVersion, and that retries replay the first response while a different expectedVersion with the same key returns `COMMON_007`.

- [ ] **Step 3: Run RED tests**

```powershell
.\gradlew.bat test --tests "*WaitingLedgerServiceTest" --tests "*WaitingCommandFacadeTest" --rerun-tasks
```

- [ ] **Step 4: Implement minimal services and DTOs**

All business writes execute inside the existing idempotency transaction boundary. Lock ordering is Store authority first, then queue/team/job row, then membership/audit/event inserts. Do not add a Store method or DTO.

- [ ] **Step 5: Run GREEN tests and architecture check**

```powershell
.\gradlew.bat test --tests "*WaitingLedgerServiceTest" --tests "*WaitingCommandFacadeTest" --tests "*ReservationProductionDependencyTest" --rerun-tasks
```

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/waiting/service backend/src/main/java/com/miriyum/domain/reservation/waiting/dto backend/src/test/java/com/miriyum/domain/reservation/waiting/service backend/src/test/java/com/miriyum/domain/reservation/ReservationProductionDependencyTest.java
git commit -m "feat(reservation): 웨이팅 FIFO 명령 서비스 구현"
```

### Task 4: Store-operator HTTP boundary and security

**Files:** `WaitingStoreOperatorController`, list/read query service and DTOs, `WaitingTeamRepository`, `ReservationSecurityConfig`, controller and repository integration tests.

**Interfaces:**

- `GET /waiting-teams` consumes optional `status`, `cursor`, `size`; size range is 1..100 and default 20.
- The list query uses keyset pagination ordered by `(queueSequence, waitingTeamId)` and fetches `size + 1`; the repository must scope every row to `storeId`, apply the optional status filter, and use the decoded cursor predicate `(queueSequence > afterQueueSequence) OR (queueSequence = afterQueueSequence AND id > afterWaitingTeamId)`.
- Team commands consume `WaitingTeamTransitionRequest(long expectedVersion)` and return `ApiResponse<WaitingTeamSnapshot>`.
- No response contains consumer account ID, phone, coordinates, idempotency key, audit actor ID, or internal database IDs beyond the public team ID.

- [ ] **Step 1: Write RED MockMvc tests**

Test authenticated success, missing/wrong namespace JWT, missing/malformed idempotency key, invalid expectedVersion, exact status/code mapping, different store access, unsupported verbs denied, and forbidden response fields. Add a MySQL repository integration test for store/status scoping, stable `(queueSequence, id)` ordering, and no duplicate or skipped rows across cursor page boundaries.

- [ ] **Step 2: Run RED controller tests**

```powershell
.\gradlew.bat test --tests "*WaitingStoreOperatorControllerTest" --rerun-tasks
```

- [ ] **Step 3: Implement controller and fail-closed security matcher**

Add a dedicated store-operator matcher for `/api/v1/store-operators/stores/*/waiting-teams/**` and `/api/v1/store-operators/stores/*/waiting-close-jobs/**`. Enumerate only contract methods and deny all others.

- [ ] **Step 4: Run GREEN controller tests**

```powershell
.\gradlew.bat test --tests "*WaitingStoreOperatorControllerTest" --tests "*ReservationSecurityConfigTest" --rerun-tasks
```

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/waiting/controller backend/src/main/java/com/miriyum/domain/reservation/waiting/service backend/src/main/java/com/miriyum/domain/reservation/waiting/dto backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingTeamRepository.java backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java backend/src/test/java/com/miriyum/domain/reservation/waiting/controller backend/src/test/java/com/miriyum/domain/reservation/waiting/repository
git commit -m "feat(reservation): 매장 웨이팅 운영 API 구현"
```

### Task 5: Closure job runtime and #271 public service contract

**Files:** closure Service/runner, closure DTOs, repositories/entities from Task 2, service tests.

**Interfaces:**

```java
WaitingActiveTeamImpact inspectActiveTeams(long operatorAccountId, long storeId);
WaitingClosureCommandResult startClosure(
        long operatorAccountId, long storeId, IdempotencyKey key, long expectedSettingsVersion);
WaitingClosureJobSnapshot getClosureJob(long operatorAccountId, long storeId, long jobId);
```

`WaitingClosureCommandResult` carries accepted HTTP status 202 and immutable job snapshot. Runner methods are package-private except one scheduler entrypoint; item batch size is 100.

- [ ] **Step 1: Write RED closure tests**

Cover zero-team immediate completion, snapshot target count, team-by-team closure, already-terminal skip, retryable item failure, permanent reconciliation count, same idempotency replay, and job ownership privacy.

- [ ] **Step 2: Run RED tests**

```powershell
.\gradlew.bat test --tests "*WaitingClosureServiceTest" --rerun-tasks
```

- [ ] **Step 3: Implement closure service and runner**

Job creation stores the target team IDs once. Processing uses `waiting_closure_job_items` and never adds later teams. Each item transaction conditionally closes one active team, removes active membership, appends audit/event, and updates counts. Final status is `COMPLETED` or `COMPLETED_WITH_RECONCILIATION`.

- [ ] **Step 4: Run GREEN tests**

```powershell
.\gradlew.bat test --tests "*WaitingClosureServiceTest" --tests "*WaitingLedgerServiceTest" --rerun-tasks
```

- [ ] **Step 5: Commit**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/waiting backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureServiceTest.java
git commit -m "feat(reservation): 웨이팅 일괄 종결 작업 구현"
```

### Task 6: MySQL concurrency and complete verification

**Files:** `WaitingLedgerConcurrencyIT` plus fixes limited to the allowlist.

**Interfaces:** validates committed behavior only; production interfaces do not change unless a test exposes a contract mismatch.

- [ ] **Step 1: Write RED MySQL concurrency scenarios**

Use barriers and separate transactions for: parallel sequence allocation, duplicate active membership, same-version call/cancel race, two operators starting the same closure key, and worker retry after a committed partial batch.

- [ ] **Step 2: Run integration RED/GREEN loop**

```powershell
.\gradlew.bat integrationTest --tests "*WaitingLedgerConcurrencyIT" --rerun-tasks
```

Expected final state: unique sequence, one active membership, one valid transition, one closure job per idempotency key, accurate reconciliation counts.

- [ ] **Step 3: Run complete backend and contract verification**

```powershell
.\gradlew.bat test --rerun-tasks
.\gradlew.bat integrationTest --rerun-tasks
.\gradlew.bat build
npx --yes @redocly/cli@2.35.1 lint docs/specs/waiting/openapi.yaml
npx --yes @redocly/cli@2.35.1 bundle docs/specs/waiting/openapi.yaml --output .superpowers/sdd/waiting-ledger-final-bundle.yaml
git diff --check origin/dev...HEAD
```

- [ ] **Step 4: Review exact scope and negative boundaries**

Confirm no consumer Waiting Controller, #271 setting production code, Store Entity/Repository import, notification/SSE code, frontend file, radius setting, seat/table field, or sequence defer command exists.

- [ ] **Step 5: Commit final test fixes**

```powershell
git add backend/src/test/java/com/miriyum/domain/reservation/waiting backend/src/main/java/com/miriyum/domain/reservation/waiting docs/specs/waiting docs/specs/store-operator-openapi.yaml
git commit -m "test(reservation): 웨이팅 원장 동시성 검증"
```

- [ ] **Step 6: Request code review and publish Draft PR**

Review against Issue #272 acceptance criteria and exact allowlist. Push only after clean status and fresh verification, then create a Draft PR targeting `dev` with `Refs #272`; do not close #272 until #271 handoff expectations are confirmed.

### Task 7: Review remediation for closure lease consumption and WAIT-011 serialization

**Files:**

- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureJobRunner.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingTeamRepository.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureJobRunnerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureServiceIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingLedgerConcurrencyIT.java`
- Update externally: PR #290 body and review replies

**Interfaces:**

- `WaitingClosureService.claimPendingItems(String owner, int limit, Duration leaseDuration)` remains unchanged; runner calls it with `limit=1` immediately before each item and stops after 100 processed claims or the first empty result.
- `WaitingLedgerService` additionally consumes `WaitingQueueSequenceRepository.findByStoreIdAndBusinessDateForUpdate(long, LocalDate)` as the call serialization mutex.
- `WaitingTeamRepository.existsByStoreIdAndBusinessDateAndStatus(long, LocalDate, WaitingTeamStatus)` detects an unresolved `CALLED` flow inside the queue-row lock.
- Existing error `WAITING_NOT_FIFO_HEAD` represents a call blocked by an earlier unresolved call; no public contract changes.

- [ ] **Step 1: Write RED runner tests**

Add tests proving the runner requests one claim at a time, processes it before requesting the next, stops on an empty claim, and never processes more than 100 claims in one poll.

- [ ] **Step 2: Run runner RED**

```powershell
.\gradlew.bat test --tests "*WaitingClosureJobRunnerTest" --rerun-tasks
```

Expected: existing runner requests 100 claims at once, so the one-at-a-time assertions fail.

- [ ] **Step 3: Implement one-at-a-time claim loop**

Replace the bulk `forEach` entrypoint with a bounded loop that calls `claimPendingItems(ownerId, 1, leaseDuration)`, returns on empty, and calls `processSafely` before the next iteration.

- [ ] **Step 4: Run runner GREEN and MySQL closure regression**

```powershell
.\gradlew.bat test --tests "*WaitingClosureJobRunnerTest" --rerun-tasks
.\gradlew.bat integrationTest --tests "*WaitingClosureServiceIT" --rerun-tasks
```

- [ ] **Step 5: Write RED WAIT-011 tests**

Add a service test that requires queue sequence locking before the unresolved-call lookup and rejects a second call with `WAITING_NOT_FIFO_HEAD`. Add a MySQL barrier test that calls two distinct FIFO teams for the same store/business date concurrently and asserts one `CALLED`, one `WAITING`, one transition audit, and one status event.

- [ ] **Step 6: Run WAIT-011 RED**

```powershell
.\gradlew.bat test --tests "*WaitingLedgerServiceTest" --rerun-tasks
.\gradlew.bat integrationTest --tests "*WaitingLedgerConcurrencyIT" --rerun-tasks
```

Expected: the service has no queue-sequence dependency or unresolved `CALLED` guard, so the new tests fail.

- [ ] **Step 7: Implement queue-row call serialization**

Inject `WaitingQueueSequenceRepository`, lock the store/business-date sequence row during `call`, reject when a `CALLED` team exists, then perform the existing FIFO-head check and transition. Add only the repository existence query; do not add migration or public error code changes.

- [ ] **Step 8: Run focused and full verification**

```powershell
.\gradlew.bat test --tests "*WaitingClosure*" --tests "*WaitingLedgerServiceTest" --rerun-tasks
.\gradlew.bat integrationTest --tests "*WaitingClosureServiceIT" --tests "*WaitingLedgerConcurrencyIT" --rerun-tasks
.\gradlew.bat test --rerun-tasks
git diff --check origin/dev...HEAD
```

- [ ] **Step 9: Commit, push, update PR body, and reply**

Commit only the listed files, push `feature/272-waiting-ledger-runtime`, update PR #290 to current Ready/CI evidence while retaining `#208 → #260 → #290` migration ordering, then reply to the actionable review with the commit and test evidence.
