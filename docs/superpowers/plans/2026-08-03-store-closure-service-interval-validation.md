# Store Closure and Service-Interval Validation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add Store-owned regular/temporary closure management and a batch contract that validates Reservation-computed service intervals against every Store schedule source.

**Architecture:** A focused `store.closure` package owns closure persistence and operator commands. A focused schedule validation service batch-loads Store, schedule, and closure sources and delegates time arithmetic to a pure policy. Reservation supplies `Instant` boundaries and receives only `ACCEPTING` or `NOT_ACCEPTING` in original input order.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, MySQL 8, Flyway, JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers, springdoc/OpenAPI YAML.

## Global Constraints

- Issue #104 is the sole implementation scope and PR target is `dev`.
- Store validates `[startAt, serviceEndAt)` only; Reservation owns duration, turnover, end-time calculation, capacity, duplicate checks, and snapshots.
- Existing PR #84 `windowEndAt` semantics remain unchanged.
- Inputs and outputs preserve order, count, and duplicates; repository calls do not scale with store count.
- Missing or inconsistent Store source data fails closed without fabricated defaults.
- Existing Flyway migrations are immutable; add the next unused migration and renumber before merge if `dev` advances.
- Do not modify Reservation, Search consumer, frontend, notification, payment, or existing-reservation impact code.
- Every production behavior begins with a failing test and is implemented minimally to green.

---

### Task 1: Closure Persistence and Domain Invariants

**Files:**
- Create: `backend/src/main/resources/db/migration/V18__create_store_closures.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/model/RegularClosureRuleType.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/model/TemporaryClosureReason.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/model/TemporaryClosureStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/entity/RegularClosureEntry.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/entity/RegularClosureVersion.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/entity/TemporaryClosure.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/entity/StoreClosureAuditEvent.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/StoreScheduleState.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/entity/RegularClosureVersionTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/entity/TemporaryClosureTest.java`

**Interfaces:**
- Produces: immutable regular-closure versions using existing `ScheduleVersionStatus`.
- Produces: temporary closure lifecycle methods `create`, `changeEndAt`, `cancel`, and `statusAt`.
- Produces: `StoreScheduleState.allocateRegularClosureVersion()` and `activateRegularClosure(long)`.

- [ ] **Step 1: Write failing entity tests**

Cover unique weekly/date rules, explicit empty schedules, draft/schedule/activate/retire/cancel transitions, `startAt < endAt`, derived temporary status boundaries, prohibited ended/cancelled mutations, and half-open overlap.

- [ ] **Step 2: Run RED entity tests**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.closure.entity.*" --no-daemon --max-workers=1
```

Expected: compilation failure because closure types do not exist.

- [ ] **Step 3: Implement minimal domain model**

Use `Instant` for temporary intervals, Store zone snapshots as canonical IANA strings, defensive immutable entry copies, and state guards that reuse `StoreErrorCode.SCHEDULE_CONFLICT`.

- [ ] **Step 4: Run GREEN entity tests**

Run the Task 1 command and require zero failures.

- [ ] **Step 5: Add migration and persistence mappings**

Create regular version/entry, temporary closure, and closure audit tables; add regular pointer/counter to `store_schedule_state`; add owner, lifecycle, due-activation, interval-overlap, and uniqueness constraints.

- [ ] **Step 6: Commit Task 1**

```powershell
git add backend/src/main/resources/db/migration/V18__create_store_closures.sql backend/src/main/java/com/miriyum/domain/store/closure backend/src/main/java/com/miriyum/domain/store/schedule/entity/StoreScheduleState.java backend/src/test/java/com/miriyum/domain/store/closure/entity
git commit -m "feat(store): 휴무 도메인과 스키마 추가"
```

### Task 2: Regular Closure Commands and Activation

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/dto/RegularClosureDraftRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/dto/RegularClosureResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/repository/RegularClosureVersionRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/repository/StoreClosureAuditEventRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/service/StoreClosureFingerprint.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/service/StoreClosureService.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/service/StoreClosureCommandFacade.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/service/RegularClosureActivationJob.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/config/RegularClosureActivationConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/StoreScheduleStateRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/dto/RegularClosureRequestTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/service/StoreClosureServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/service/RegularClosureActivationJobTest.java`

**Interfaces:**
- Consumes: existing `SchedulePublicationRequest`, `SchedulePublicationCancellationRequest`, Store schedule authority, global idempotency executor, and Store-first locking.
- Produces: create draft, immediate/scheduled publish, publication cancellation, and due activation commands.

- [ ] **Step 1: Write failing request and service tests**

Test null/duplicate/over-limit rules, explicit empty draft, ownership, monotonic version allocation, immediate/scheduled publication, cancellation, idempotent replay, previous-active retirement, and permanent activation failure.

- [ ] **Step 2: Run RED command tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.closure.dto.RegularClosureRequestTest" --tests "com.miriyum.domain.store.closure.service.StoreClosureServiceTest" --tests "com.miriyum.domain.store.closure.service.RegularClosureActivationJobTest" --no-daemon --max-workers=1
```

- [ ] **Step 3: Implement command path**

Follow existing Store schedule fingerprint, transaction, audit, result-envelope, and conflict-translation patterns. Never seed an implicit empty active version.

- [ ] **Step 4: Run GREEN command tests**

Run the Task 2 command and require zero failures.

- [ ] **Step 5: Commit Task 2**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/closure backend/src/main/java/com/miriyum/domain/store/schedule/repository/StoreScheduleStateRepository.java backend/src/test/java/com/miriyum/domain/store/closure
git commit -m "feat(store): 정기 휴무 게시 수명주기 구현"
```

### Task 3: Temporary Closure Commands

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/dto/TemporaryClosureCreateRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/dto/TemporaryClosureEndAtRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/dto/TemporaryClosureCancellationRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/dto/TemporaryClosureResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/repository/TemporaryClosureRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/closure/service/StoreClosureService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/closure/service/StoreClosureCommandFacade.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/closure/service/StoreClosureFingerprint.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/dto/TemporaryClosureRequestTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/service/TemporaryClosureServiceTest.java`

**Interfaces:**
- Produces: create, end-at change, and cancellation commands with idempotent results.
- Consumes: active regular closure rules to reject redundant overlapping temporary closures.

- [ ] **Step 1: Write failing temporary command tests**

Cover offset timestamp conversion, invalid intervals/messages, derived response status, immediate/future creation, overlap rejection, end shortening/extension, ended/cancelled guards, lock ordering, audit, and replay.

- [ ] **Step 2: Run RED temporary tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.closure.dto.TemporaryClosureRequestTest" --tests "com.miriyum.domain.store.closure.service.TemporaryClosureServiceTest" --no-daemon --max-workers=1
```

- [ ] **Step 3: Implement temporary command path**

Persist real instants and a zone snapshot, derive status from `Clock`, query overlap with half-open predicates, and append audit in the same transaction.

- [ ] **Step 4: Run GREEN temporary tests**

Run the Task 3 command and require zero failures.

- [ ] **Step 5: Commit Task 3**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/closure backend/src/test/java/com/miriyum/domain/store/closure
git commit -m "feat(store): 임시 휴점 명령 구현"
```

### Task 4: Pure Service-Interval Policy

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/StoreServiceIntervalRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/StoreServiceIntervalResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/StoreServiceIntervalStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreServiceIntervalPolicy.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/dto/StoreServiceIntervalContractTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreServiceIntervalPolicyTest.java`

**Interfaces:**
- Produces: immutable public value contract and pure acceptance calculation over preloaded Store schedule snapshots.

- [ ] **Step 1: Write failing DTO and policy tests**

Use hand-derived fixtures for business containment, split-business rejection, break/regular/temporary overlap, touching boundaries, Sunday-to-Monday, zone conversion, DST gap/overlap boundaries, and invalid/missing sources.

- [ ] **Step 2: Run RED policy tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.dto.StoreServiceIntervalContractTest" --tests "com.miriyum.domain.store.schedule.service.StoreServiceIntervalPolicyTest" --no-daemon --max-workers=1
```

- [ ] **Step 3: Implement minimal pure policy**

Materialize weekly boundaries on owning local dates, require exactly one valid offset per local boundary, compare real `Instant` half-open intervals, and return false rather than throw for source inconsistency.

- [ ] **Step 4: Run GREEN policy tests**

Run the Task 4 command and require zero failures.

- [ ] **Step 5: Commit Task 4**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule/dto/StoreServiceInterval* backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreServiceIntervalPolicy.java backend/src/test/java/com/miriyum/domain/store/schedule/dto/StoreServiceIntervalContractTest.java backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreServiceIntervalPolicyTest.java
git commit -m "feat(store): 서비스 구간 판정 정책 추가"
```

### Task 5: Batch Validation Service and Repository Queries

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreServiceIntervalValidationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/repository/StoreRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/OperatingScheduleVersionRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/closure/repository/RegularClosureVersionRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/closure/repository/TemporaryClosureRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreServiceIntervalValidationServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/repository/StoreServiceIntervalRepositoryIT.java`

**Interfaces:**
- Consumes: DTO/policy from Task 4 and batch repositories.
- Produces: `validateServiceIntervals(List<StoreServiceIntervalRequest>)` preserving original positions.

- [ ] **Step 1: Write failing service tests**

Test null/empty input, input order/duplicates, two stores with different ends, all fail-closed branches, no per-store repository call, and unchanged Store state/audit.

- [ ] **Step 2: Run RED service tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationServiceTest" --no-daemon --max-workers=1
```

- [ ] **Step 3: Implement fixed-query batch composition**

Deduplicate IDs for six bounded reads, map by owner/version, query temporary closures by the aggregate envelope, and reconstruct results from the original request sequence.

- [ ] **Step 4: Run GREEN service tests**

Run the Task 5 service command and require zero failures.

- [ ] **Step 5: Add MySQL repository/query-count integration test**

Persist two Stores with different schedules and closures, validate duplicated inputs, and assert the fixed prepared-statement ceiling with Hibernate Statistics.

- [ ] **Step 6: Run repository integration test**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.repository.StoreServiceIntervalRepositoryIT" --no-daemon --max-workers=1
```

- [ ] **Step 7: Commit Task 5**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/core/repository/StoreRepository.java backend/src/main/java/com/miriyum/domain/store/schedule backend/src/main/java/com/miriyum/domain/store/closure/repository backend/src/test/java/com/miriyum/domain/store/schedule
git commit -m "feat(store): 예약 서비스 구간 batch 검증 구현"
```

### Task 6: HTTP, OpenAPI, Active Specs, and End-to-End Verification

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/closure/controller/StoreClosureController.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/controller/StoreClosureControllerTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/closure/controller/StoreClosureOpenApiContractTest.java`
- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/store-search/openapi.yaml`
- Modify only if mechanics need clarification: `docs/service-policies/03-store-operation.md`

**Interfaces:**
- Consumes: closure command services and shared API envelope/authentication conventions.
- Produces: six operator routes documented in the design and matching OpenAPI schemas.

- [ ] **Step 1: Write failing MockMvc and OpenAPI tests**

Cover authenticated owner success, foreign Store denial, validation, missing/invalid idempotency key, existing error envelopes, all six routes, and exact schema/route parity.

- [ ] **Step 2: Run RED web contract tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.closure.controller.*" --no-daemon --max-workers=1
```

- [ ] **Step 3: Implement controller and active contract documentation**

Use existing Store operator principal, `ApiResponse`, `ScheduleCommandResult`, and existing errors. Add no internal batch HTTP route.

- [ ] **Step 4: Run GREEN web contract tests**

Run the Task 6 command and require zero failures.

- [ ] **Step 5: Run focused Store verification**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.closure.*" --tests "com.miriyum.domain.store.schedule.*" --no-daemon --max-workers=1
```

- [ ] **Step 6: Run compile, migration, and full verification**

```powershell
.\gradlew.bat compileJava --no-daemon --max-workers=1
.\gradlew.bat clean test --no-daemon --max-workers=1
```

Inspect every `TEST-*.xml` and require failures `0`, errors `0`, and skipped `0`. If the full command exceeds the environment limit, report the timeout separately and do not convert focused success into a full-suite claim.

- [ ] **Step 7: Verify scope and whitespace**

```powershell
git diff --check origin/dev...HEAD
git diff --name-only origin/dev...HEAD
```

Confirm no Reservation, Search consumer, frontend, existing migration, notification, or payment path changed.

- [ ] **Step 8: Commit Task 6**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/closure/controller backend/src/test/java/com/miriyum/domain/store/closure/controller docs/specs/store-search docs/service-policies/03-store-operation.md
git commit -m "feat(store): 휴무 운영 API 계약 완성"
```

- [ ] **Step 9: Review Issue #104 acceptance criteria**

Map each criterion to production code and a passing test. Record any unmet criterion as blocked; do not open a ready PR with a silent gap.
