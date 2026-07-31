# Store Schedule Publication Lifecycle Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Store 영업시간·예약 접수 시간대를 불변 초안으로 저장한 뒤 즉시 또는 미래 시각에 게시하고, 효력 전 예약 게시 취소·다중 인스턴스 자동 활성화·append-only 감사를 제공한다.

**Architecture:** 기존 `StoreScheduleService`가 초안·게시·취소 command transaction을 조정하고 `StoreScheduleActivationJob`이 중앙 DB에서 도래 후보를 읽어 같은 서비스의 독립 transaction을 호출한다. 각 transaction은 Store 행 다음 `store_schedule_state` 행을 잠그고 버전 상태를 재검증하며, 버전 상태·활성 포인터·감사·멱등 결과를 원자적으로 확정한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA, Bean Validation, Flyway, MySQL 8.0.40 Testcontainers, JUnit 5, AssertJ, Mockito, MockMvc, Gradle 9.6.1 Wrapper

## Global Constraints

- 활성 계약은 `docs/service-policies/03-store-operation.md`, `docs/specs/store-search/spec.md`, `docs/specs/store-search/openapi.yaml`이다.
- 기존 V9·V10 migration은 수정하지 않고 V11·V12만 추가한다.
- 일정 시각은 `Clock`의 `Instant`와 매장 IANA `timeZoneId`를 사용하며 JVM 기본 시간대를 사용하지 않는다.
- Store → schedule state 순서의 잠금을 모든 신규 실행과 자동 활성화에서 유지한다.
- 실제 예약·홀드·행사 충돌 목록은 #69 범위이며 이 계획은 `NOT_EVALUATED`와 nullable 충돌 수만 저장한다.
- 기존 확정 거래를 자동 이동·취소하거나 다른 도메인의 Entity·Repository를 참조하지 않는다.
- 테스트는 실패를 먼저 확인한 뒤 최소 구현으로 통과시키고 각 task를 별도 커밋한다.

---

### Task 1: 매장 IANA 시간대

**Files:**
- Create: `backend/src/main/resources/db/migration/V11__add_store_time_zone.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreScheduleAuthority.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreCreateRequest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/dto/ManagedStoreResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandFingerprint.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/dto/ManagedStoreResponseTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/entity/StoreTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreCommandFingerprintTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/repository/StoreRepositoryIT.java`

**Interfaces:**
- Produces: `StoreScheduleAuthority(long storeId, String timeZoneId)`
- Produces: `StoreService.requireSchedulePublicationAuthority(long operatorId, long storeId): StoreScheduleAuthority`
- Produces: `StoreService.requireScheduledActivationAuthority(long storeId): StoreScheduleAuthority`

- [ ] **Step 1: Write failing request/entity/response tests**

```java
@Test
void createRejectsUnknownIanaTimeZone() {
    assertThatThrownBy(() -> Store.create(
            11L, "1234567890", BusinessType.CAFE, "매장", "",
            Region.SEOUL, "주소", "CAFE", Set.of(),
            true, false, false, "Mars/Olympus",
            ACCEPTED_AT, TERMS_VERSION))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void managedResponseExposesStoreTimeZone() {
    assertThat(ManagedStoreResponse.from(store).timeZoneId())
            .isEqualTo("Asia/Seoul");
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.*"
```

Expected: compilation/test failure because `timeZoneId` and `StoreScheduleAuthority` do not exist.

- [ ] **Step 3: Add V11 and minimal Store implementation**

```sql
ALTER TABLE stores ADD COLUMN time_zone_id VARCHAR(64) NULL AFTER address;
UPDATE stores SET time_zone_id = 'Asia/Seoul' WHERE time_zone_id IS NULL;
ALTER TABLE stores MODIFY COLUMN time_zone_id VARCHAR(64) NOT NULL;
```

`Store.create` validates with `ZoneId.of(timeZoneId).getId()`, persists the canonical ID, and `StoreCreateRequest` requires a nonblank maximum-64-character value. `StoreUpdateRequest` does not expose time-zone changes.

- [ ] **Step 4: Return the locked authority value**

```java
public record StoreScheduleAuthority(long storeId, String timeZoneId) {
}

public StoreScheduleAuthority requireSchedulePublicationAuthority(
        long operatorAccountId,
        long storeId
) {
    Store store = loadManagedStoreForUpdate(operatorAccountId, storeId);
    requireScheduleState(store);
    return new StoreScheduleAuthority(store.getId(), store.getTimeZoneId());
}
```

`requireScheduledActivationAuthority` locks by store ID, rejects non-`APPROVED` or `CLOSED`, and returns the same record without inventing a system operator.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.*"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/resources/db/migration/V11__add_store_time_zone.sql backend/src/main/java/com/miriyum/domain/store/core backend/src/test/java/com/miriyum/domain/store/core
git commit -m "feat(store): persist store time zones"
```

### Task 2: 일정 버전 상태와 감사 영속성

**Files:**
- Create: `backend/src/main/resources/db/migration/V12__add_store_schedule_lifecycle.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/model/ScheduleVersionStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/model/ScheduleStream.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/model/ScheduleAuditAction.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/model/ConflictCheckStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/StoreScheduleAuditEvent.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/StoreScheduleAuditEventRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/OperatingScheduleVersion.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/ReservationScheduleVersion.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/OperatingScheduleVersionRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/ReservationScheduleVersionRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/entity/StoreScheduleVersionTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/repository/StoreScheduleRepositoryIT.java`

**Interfaces:**
- Produces: `OperatingScheduleVersion.createDraft(storeId, version, timeZoneId, intervals)`
- Produces: `ReservationScheduleVersion.createDraft(storeId, version, operatingVersionId, timeZoneId, intervals)`
- Produces on both entities: `schedule(Instant, String)`, `activate(Instant)`, `retire()`, `cancelPublication()`, `failActivation()`
- Produces: `StoreScheduleAuditEvent.record(...)`

- [ ] **Step 1: Write failing lifecycle tests**

```java
@Test
void scheduledDraftCanReturnToDraftBeforeActivation() {
    OperatingScheduleVersion version = operatingDraft();
    version.schedule(Instant.parse("2026-08-01T03:00:00Z"), "여름 영업시간");

    version.cancelPublication();

    assertThat(version.getStatus()).isEqualTo(ScheduleVersionStatus.DRAFT);
    assertThat(version.getEffectiveAt()).isNull();
}

@Test
void activeVersionCannotBeCancelled() {
    OperatingScheduleVersion version = operatingDraft();
    version.activate(Instant.parse("2026-08-01T00:00:00Z"));

    assertThatThrownBy(version::cancelPublication)
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getErrorCode())
            .isEqualTo(StoreErrorCode.SCHEDULE_CONFLICT);
}
```

- [ ] **Step 2: Run entity tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.entity.*"
```

Expected: compilation failure because lifecycle types and methods do not exist.

- [ ] **Step 3: Add V12 lifecycle columns and audit table**

V12 backfills existing versions as `ACTIVE`, copies `published_at` to `activated_at`, stores `effective_at`/`activated_at` as `TIMESTAMP(6)`, adds `(store_id, status, effective_at, version_number)` due indexes and unique `(store_id, effective_at)` constraints for scheduled instants, then creates `store_schedule_audit_events` with a restrictive Store FK.

- [ ] **Step 4: Implement explicit entity transitions**

```java
public void schedule(Instant effectiveAt, String changeReason) {
    requireStatus(ScheduleVersionStatus.DRAFT);
    this.status = ScheduleVersionStatus.SCHEDULED;
    this.effectiveAt = effectiveAt;
    this.changeReason = changeReason;
}

public void activate(Instant activatedAt) {
    if (status != ScheduleVersionStatus.DRAFT
            && status != ScheduleVersionStatus.SCHEDULED) {
        throw scheduleConflict();
    }
    this.status = ScheduleVersionStatus.ACTIVE;
    this.effectiveAt = activatedAt;
    this.activatedAt = activatedAt;
}
```

The two version classes keep interval content and `timeZoneId` immutable after construction. `cancelPublication` clears `effectiveAt` and `changeReason` because a later publication command must provide a new reason.

- [ ] **Step 5: Add real MySQL mapping assertions**

Persist a draft, scheduled version and audit event, reload them, and assert status, UTC instant, IANA zone, nullable conflict count and `NOT_EVALUATED`.

- [ ] **Step 6: Run entity/repository tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.entity.*" --tests "com.miriyum.domain.store.schedule.repository.StoreScheduleRepositoryIT"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/resources/db/migration/V12__add_store_schedule_lifecycle.sql backend/src/main/java/com/miriyum/domain/store/schedule backend/src/test/java/com/miriyum/domain/store/schedule/entity backend/src/test/java/com/miriyum/domain/store/schedule/repository/StoreScheduleRepositoryIT.java
git commit -m "feat(store): model schedule publication lifecycle"
```

### Task 3: 전체 주간 초안 저장

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/OperatingHoursResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/ReservationTimeSlotsResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleFingerprint.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleCommandFacade.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/dto/StoreScheduleResponseTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleFingerprintTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleServiceTest.java`

**Interfaces:**
- Produces: `createOperatingDraft(operatorId, storeId, key, request)`
- Produces: `createReservationDraft(operatorId, storeId, key, request)`
- Response fields: `version`, `status`, `timeZoneId`, nullable `effectiveAt`, nullable `changeReason`, `conflictCheckStatus`, nullable `conflictCount`, `days`

- [ ] **Step 1: Replace immediate-publication tests with failing draft tests**

```java
@Test
void operatingPutCreatesDraftWithoutChangingActivePointer() {
    ScheduleCommandResult<OperatingHoursResponse> result =
            scheduleService.createOperatingDraft(
                    OPERATOR_ID, STORE_ID, key(), operatingRequest());

    assertThat(result.data().status()).isEqualTo(ScheduleVersionStatus.DRAFT);
    assertThat(state.getActiveOperatingScheduleVersionId()).isEqualTo(OLD_ACTIVE_ID);
    assertThat(audit.getAction()).isEqualTo(ScheduleAuditAction.DRAFT_CREATED);
}
```

Reservation draft tests assert that the active operating version ID is captured and that no active reservation pointer changes.

- [ ] **Step 2: Run service tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.service.StoreScheduleServiceTest"
```

Expected: compilation failure for draft methods and response fields.

- [ ] **Step 3: Implement draft commands**

Use idempotency command types `STORE_OPERATING_HOURS_DRAFT_CREATE` and `STORE_RESERVATION_TIME_SLOTS_DRAFT_CREATE`. Inside the new execution callback: lock Store authority, initialize/lock schedule state, validate all seven days, allocate a version, save `DRAFT`, append `DRAFT_CREATED`, and return without calling either `activate...` state method.

- [ ] **Step 4: Update fingerprints and replay conversion**

The draft fingerprint includes route identity, store ID and canonical weekly content. Response serialization includes all OpenAPI lifecycle fields so replay returns the original draft result.

- [ ] **Step 5: Run service/DTO/fingerprint tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.dto.*" --tests "com.miriyum.domain.store.schedule.service.StoreScheduleFingerprintTest" --tests "com.miriyum.domain.store.schedule.service.StoreScheduleServiceTest"
```

Expected: PASS.

- [ ] **Step 6: Commit**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule backend/src/test/java/com/miriyum/domain/store/schedule
git commit -m "feat(store): save schedule drafts"
```

### Task 4: 즉시·예약 게시와 취소 command

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/SchedulePublicationRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/SchedulePublicationCancellationRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/model/PublicationMode.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/OperatingScheduleVersionRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/ReservationScheduleVersionRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleFingerprint.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleCommandFacade.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreSchedulePublicationIT.java`

**Interfaces:**
- Produces: `publishOperating(operatorId, storeId, version, key, request)`
- Produces: `publishReservation(operatorId, storeId, version, key, request)`
- Produces: `cancelOperatingPublication(operatorId, storeId, version, key, request)`
- Produces: `cancelReservationPublication(operatorId, storeId, version, key, request)`

- [ ] **Step 1: Write failing immediate-publication transition test**

```java
@Test
void immediateOperatingPublicationActivatesDraftAndRetiresPreviousVersion() {
    OperatingHoursResponse response = scheduleService.publishOperating(
            OPERATOR_ID, STORE_ID, 2L, key(),
            new SchedulePublicationRequest(
                    PublicationMode.IMMEDIATE, null, "여름 영업시간"));

    assertThat(response.status()).isEqualTo(ScheduleVersionStatus.ACTIVE);
    assertThat(previous.getStatus()).isEqualTo(ScheduleVersionStatus.RETIRED);
    assertThat(state.getActiveOperatingScheduleVersionId()).isEqualTo(draft.getId());
    assertThat(state.getActiveReservationScheduleVersionId()).isNull();
}
```

- [ ] **Step 2: Run the immediate test and verify RED**

Run the exact method with:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.service.StoreScheduleServiceTest.immediateOperatingPublicationActivatesDraftAndRetiresPreviousVersion"
```

Expected: compilation failure because publication API does not exist.

- [ ] **Step 3: Implement immediate publication minimally and verify GREEN**

Load the target by `(storeId, versionNumber)`, require `DRAFT`, revalidate reservation drafts against the current active operating ID, retire the previous active version, activate the target at `clock.instant()`, update pointers and append `IMMEDIATE_PUBLISHED`.

- [ ] **Step 4: Write failing scheduled-publication tests**

Cover future-only `effectiveAt`, same-stream duplicate effective instant, preservation of the current active pointer, and `PUBLICATION_SCHEDULED` audit.

- [ ] **Step 5: Implement scheduled publication and verify GREEN**

Require `effectiveAt.isAfter(clock.instant())`, set `SCHEDULED`, and do not update active pointers.

- [ ] **Step 6: Write failing cancellation tests**

Cover `SCHEDULED → DRAFT`, cleared effective time, mandatory reason, idempotent replay, and rejection after activation.

- [ ] **Step 7: Implement cancellation and verify GREEN**

Append `SCHEDULED_PUBLICATION_CANCELLED` in the same transaction. A different key against a no-longer-scheduled version returns `STORE_006`; the original key replays its success.

- [ ] **Step 8: Run focused service and MySQL tests**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.service.StoreScheduleServiceTest" --tests "com.miriyum.domain.store.schedule.service.StoreSchedulePublicationIT"
```

Expected: PASS.

- [ ] **Step 9: Commit**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule backend/src/test/java/com/miriyum/domain/store/schedule
git commit -m "feat(store): publish and cancel schedule drafts"
```

### Task 5: 다중 인스턴스 자동 활성화

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/config/StoreScheduleActivationConfig.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleActivationJob.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/OperatingScheduleVersionRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/ReservationScheduleVersionRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleService.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleActivationJobTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreSchedulePublicationIT.java`

**Interfaces:**
- Produces: `activateDueOperating(long versionId): void`
- Produces: `activateDueReservation(long versionId): void`
- Produces: `StoreScheduleActivationJob.activateDueSchedules(): void`

- [ ] **Step 1: Write failing due-order and duplicate-worker tests**

Two workers read the same due IDs. The tests assert one `ACTIVE` transition, one prior-version retirement and one `SCHEDULE_ACTIVATED` audit. A separate fixture with two overdue versions asserts activation order `(effectiveAt, versionNumber)` and the later version as final active.

- [ ] **Step 2: Run MySQL test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.service.StoreSchedulePublicationIT.*scheduled*"
```

Expected: failure because no due activation exists.

- [ ] **Step 3: Implement candidate polling and transactional activation**

Repositories return bounded due IDs ordered by `effectiveAt, versionNumber`. Each service call locks Store then state, checks that the target is still the earliest due version for the stream and uses entity status as the conditional transition gate. Duplicate or stale workers return without another audit.

- [ ] **Step 4: Implement permanent failure and transient retry**

Invalid Store state or reservation-to-operating-version mismatch transitions to `ACTIVATION_FAILED` plus `SCHEDULE_ACTIVATION_FAILED` audit. Lock/connection exceptions escape without state mutation so the next poll retries.

- [ ] **Step 5: Add scheduled trigger**

`StoreScheduleActivationConfig` enables scheduling. `StoreScheduleActivationJob` uses:

```java
@Scheduled(fixedDelayString =
        "${miriyum.store.schedule.activation-delay-ms:1000}")
public void activateDueSchedules() {
    // bounded operating and reservation batches
}
```

Unit tests call the method directly with fixed `Clock`; they do not sleep.

- [ ] **Step 6: Run job and MySQL tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.service.StoreScheduleActivationJobTest" --tests "com.miriyum.domain.store.schedule.service.StoreSchedulePublicationIT"
```

Expected: PASS.

- [ ] **Step 7: Commit**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule backend/src/main/resources/application.yml backend/src/test/java/com/miriyum/domain/store/schedule
git commit -m "feat(store): activate scheduled publications"
```

### Task 6: HTTP command 계약

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/controller/StoreScheduleController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/SchedulePublicationRequest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/SchedulePublicationCancellationRequest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/controller/StoreScheduleControllerTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java`

**Interfaces:**
- Consumes all Task 3/4 facade methods.
- Exposes the six paths and payloads already declared in `docs/specs/store-search/openapi.yaml`.

- [ ] **Step 1: Write failing MockMvc tests for draft responses**

Assert PUT calls draft methods, message says “초안이 저장되었습니다”, and response contains `DRAFT`, `Asia/Seoul`, `NOT_EVALUATED`, null conflict count and canonical `HH:mm`.

- [ ] **Step 2: Run controller tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.controller.StoreScheduleControllerTest"
```

Expected: failure because the controller still calls immediate replacement methods.

- [ ] **Step 3: Implement draft endpoints and verify GREEN**

Rename controller methods to `createOperatingHoursDraft` and `createReservationTimeSlotsDraft` without changing their PUT paths.

- [ ] **Step 4: Write failing publication/cancellation contract tests**

Cover IMMEDIATE rejecting `effectiveAt`, SCHEDULED requiring a future offset date-time, blank `changeReason`, positive store/version path IDs, missing/invalid idempotency key, authentication namespaces, and 404/409 envelopes.

- [ ] **Step 5: Implement four POST endpoints and verify GREEN**

Use `@Positive` on both IDs and `@Valid` request records. Parse `effectiveAt` as `OffsetDateTime` and convert to `Instant` in the DTO/service boundary.

- [ ] **Step 6: Add Store create time-zone HTTP tests**

Assert missing/unknown `timeZoneId` is 400 and valid `Asia/Seoul` appears in the managed response.

- [ ] **Step 7: Run Store controller suites**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.controller.StoreControllerTest" --tests "com.miriyum.domain.store.schedule.controller.StoreScheduleControllerTest"
```

Expected: PASS.

- [ ] **Step 8: Commit**

```powershell
git add backend/src/main/java/com/miriyum/domain/store backend/src/test/java/com/miriyum/domain/store
git commit -m "feat(store): expose schedule publication commands"
```

### Task 7: Migration·경합·회귀 완료

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/repository/StoreScheduleRepositoryIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreSchedulePublicationIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleCommandFacadeTest.java`
- Modify: `docs/superpowers/plans/2026-07-31-store-schedule-issue-34.md`

**Interfaces:**
- Verifies the complete active spec and OpenAPI behavior without introducing new production interfaces.

- [ ] **Step 1: Add failing migration-backfill test**

The test migrates a V10 fixture and asserts existing stores receive `Asia/Seoul`, existing schedule versions become `ACTIVE`, old publication time becomes activation time, and no audit falsely claims evaluated conflicts.

- [ ] **Step 2: Add failing concurrency tests**

Use barriers/latches for:

- immediate publish versus Store close,
- scheduled activation versus cancellation,
- two workers activating one version,
- two different scheduled instants processed after downtime,
- failure rollback across version state, pointer, audit and idempotency record.

- [ ] **Step 3: Run focused integration tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.*"
```

Expected: one or more new assertions fail before the final transaction/constraint corrections.

- [ ] **Step 4: Make minimal lock/index/exception corrections**

Keep Store → state lock order, translate only known lifecycle constraint markers to `STORE_006`, and leave unrelated persistence failures visible.

- [ ] **Step 5: Run focused tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.*"
```

Expected: PASS with zero failures/errors/skips.

- [ ] **Step 6: Run full verification**

Run:

```powershell
.\gradlew.bat clean build
git diff --check
git status --short
```

Expected: backend build success, no diff whitespace errors, and only Issue #34 allowlisted changes.

- [ ] **Step 7: Mark the prior immediate-only plan superseded and commit**

```powershell
git add backend docs/superpowers/plans/2026-07-31-store-schedule-issue-34.md
git commit -m "test(store): verify schedule publication lifecycle"
```

