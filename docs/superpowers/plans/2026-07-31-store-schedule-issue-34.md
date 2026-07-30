# Store Schedule Issue #34 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement immediately published, immutable weekly operating-hours and reservation-time-slot versions for authenticated store operators.

**Architecture:** A per-store coordination row owns both active-version pointers and serializes publication. Operating and reservation configurations are separate immutable version aggregates; each reservation version records the operating version used for validation. A pure weekly interval policy handles overnight ownership, cyclic-week overlap, break containment, and reservation containment.

**Tech Stack:** Java 21, Spring Boot 4, Spring MVC/Security, Spring Data JPA, Flyway, MySQL 8.0, JUnit 5, AssertJ, Mockito, Testcontainers

## Global Constraints

- Work only in `codex/34-store-schedule`, based on `codex/33-store-core`.
- Use package root `com.miriyum.domain.store.schedule`.
- All local time calculations use explicit `Asia/Seoul`; never use the system default time zone.
- `startTime > endTime` is an overnight interval owned by the start day.
- `startTime == endTime` is invalid.
- PUT always publishes a complete seven-day configuration immediately.
- Historical versions are immutable.
- Existing confirmed reservations are never changed, cancelled, or moved.
- Reservation capacity, duration, turnover, holidays, public search, menus, and frontend are out of scope.
- Reuse #33 `StoreService.requireManagementAuthority` and #32 `IdempotencyExecutor`.
- Do not change `miriyum.identity-verification.dev-stub-enabled`.

---

### Task 1: Weekly Request Contracts and Interval Policy

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/TimeRangeRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/DailyOperatingScheduleRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/WeeklyOperatingHoursRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/DailyReservationSlotsRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/WeeklyReservationTimeSlotsRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/model/ScheduleIntervalKind.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/model/WeeklyInterval.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/service/WeeklySchedulePolicy.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/dto/WeeklyScheduleRequestTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/WeeklySchedulePolicyTest.java`

**Interfaces:**
- Consumes: Java `DayOfWeek`, `LocalTime`, `StoreErrorCode.SCHEDULE_CONFLICT`
- Produces:
  - `List<WeeklyInterval> validateOperating(WeeklyOperatingHoursRequest request)`
  - `List<WeeklyInterval> validateReservation(WeeklyReservationTimeSlotsRequest request, List<WeeklyInterval> operating)`

- [ ] **Step 1: Write failing Bean Validation tests**

Create requests using exactly seven unique days and assert that six days, eight days,
duplicate days, null arrays, and null interval boundaries are rejected. The key
assertion is:

```java
assertThat(validator.validate(request))
        .extracting(ConstraintViolation::getPropertyPath)
        .isNotEmpty();
```

Use a record-level `@AssertTrue` method named `hasEachDayExactlyOnce` on both weekly
request records. Arrays use `@NotNull`, `@Size(min = 7, max = 7)`, and `@Valid`.

- [ ] **Step 2: Run request tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.dto.WeeklyScheduleRequestTest"
```

Expected: compilation fails because the request records do not exist.

- [ ] **Step 3: Implement request records**

Use these exact shapes:

```java
public record TimeRangeRequest(
        @NotNull LocalTime startTime,
        @NotNull LocalTime endTime) {}

public record DailyOperatingScheduleRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull @Valid List<TimeRangeRequest> businessHours,
        @NotNull @Valid List<TimeRangeRequest> breakTimes) {}

public record DailyReservationSlotsRequest(
        @NotNull DayOfWeek dayOfWeek,
        @NotNull @Valid List<TimeRangeRequest> slots) {}
```

The two weekly records expose `days()` and validate that the set of non-null
`dayOfWeek` values equals `EnumSet.allOf(DayOfWeek.class)`.

- [ ] **Step 4: Run request tests and verify GREEN**

Run the focused request test command from Step 2. Expected: PASS.

- [ ] **Step 5: Write failing interval policy tests**

Cover:

```java
@Test void acceptsMonday2200ToTuesday0200();
@Test void rejectsEqualStartAndEnd();
@Test void allowsAdjacentHalfOpenRanges();
@Test void rejectsOverlapAcrossMidnight();
@Test void rejectsSundayOvernightOverlapWithMonday();
@Test void rejectsBreakOutsideBusinessRange();
@Test void rejectsOverlappingBreaks();
@Test void acceptsReservationInsideBusinessAndOutsideBreak();
@Test void rejectsReservationCrossingBreak();
@Test void rejectsReservationOutsideBusiness();
```

Each semantic failure must assert `ServiceException.getErrorCode()` equals
`StoreErrorCode.SCHEDULE_CONFLICT`.

- [ ] **Step 6: Run policy tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.service.WeeklySchedulePolicyTest"
```

Expected: compilation fails because policy and model types do not exist.

- [ ] **Step 7: Implement normalized weekly intervals**

`WeeklyInterval` has:

```java
public record WeeklyInterval(
        DayOfWeek dayOfWeek,
        LocalTime startTime,
        LocalTime endTime,
        boolean overnight,
        ScheduleIntervalKind kind,
        int weekStartMinute,
        int weekEndMinute) {}
```

Normalize with Monday index zero:

```java
int start = day.getValue() - 1;
start = start * 1440 + startTime.getHour() * 60 + startTime.getMinute();
int durationEnd = endTime.getHour() * 60 + endTime.getMinute();
boolean overnight = !endTime.isAfter(startTime);
int end = (day.getValue() - 1) * 1440 + durationEnd + (overnight ? 1440 : 0);
```

Reject equality before computing the overnight flag. Detect cyclic overlap by testing
each interval against copies shifted by `-10080`, `0`, and `+10080`. Adjacent
half-open ranges do not overlap:

```java
private boolean overlaps(int aStart, int aEnd, int bStart, int bEnd) {
    return aStart < bEnd && bStart < aEnd;
}
```

Breaks must be contained in one business interval using
`business.start <= break.start && break.end <= business.end`. Reservation intervals
use the same containment rule and must not overlap breaks.

- [ ] **Step 8: Run Task 1 tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.dto.*Test" --tests "com.miriyum.domain.store.schedule.service.WeeklySchedulePolicyTest"
```

Expected: all Task 1 tests pass.

- [ ] **Step 9: Commit Task 1**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule backend/src/test/java/com/miriyum/domain/store/schedule
git commit -m "feat(store): validate weekly store schedules"
```

---

### Task 2: Flyway Schema and Immutable Persistence Aggregates

**Files:**
- Create: `backend/src/main/resources/db/migration/V9__create_store_schedule_versions.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/StoreScheduleState.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/OperatingScheduleVersion.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/OperatingScheduleEntry.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/ReservationScheduleVersion.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/ReservationScheduleEntry.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/StoreScheduleStateRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/OperatingScheduleVersionRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/repository/ReservationScheduleVersionRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/repository/StoreScheduleRepositoryIT.java`

**Interfaces:**
- Consumes: `WeeklyInterval`
- Produces:
  - `StoreScheduleStateRepository.initialize(long storeId)`
  - `Optional<StoreScheduleState> findForUpdateByStoreId(long storeId)`
  - immutable version factories and `state.activateOperating(id)`,
    `state.activateReservation(id)`

- [ ] **Step 1: Write failing MySQL repository tests**

Use the repository's established `@Testcontainers` and `@SpringBootTest` pattern with
`mysql:8.0.40`. Test:

```java
@Test void flywaySchemaMatchesJpaMappings();
@Test void activatingNewVersionKeepsPreviousVersionRowsUnchanged();
@Test void operatingAndReservationCountersAdvanceIndependently();
@Test void reservationVersionReferencesValidatedOperatingVersion();
@Test void deletingReferencedStoreIsRestricted();
```

The immutable history test stores version 1, activates version 2, clears the
`EntityManager`, and asserts both versions and their original child rows remain.

- [ ] **Step 2: Run repository test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.repository.StoreScheduleRepositoryIT"
```

Expected: compilation fails because repositories and entities do not exist.

- [ ] **Step 3: Create V9 schema**

Create these tables:

- `store_schedule_state`
- `store_operating_schedule_versions`
- `store_operating_schedule_entries`
- `store_reservation_schedule_versions`
- `store_reservation_schedule_entries`

Required constraints:

```sql
UNIQUE (store_id, version_number)
CHECK (version_number >= 1)
CHECK (start_time <> end_time)
CHECK (interval_kind IN ('BUSINESS_HOURS', 'BREAK_TIME'))
FOREIGN KEY (store_id) REFERENCES stores(store_id) ON DELETE RESTRICT
FOREIGN KEY (validated_operating_version_id)
  REFERENCES store_operating_schedule_versions(operating_schedule_version_id)
  ON DELETE RESTRICT
```

The state table stores nullable active IDs with restrictive FKs and non-null next
counters defaulting to 1. Entry tables use `(version_id, entry_order)` as their
primary key.

- [ ] **Step 4: Implement JPA aggregates**

Use scalar IDs between aggregates. Version entities own ordered
`@ElementCollection` entries and expose unmodifiable copies. Factory methods accept
the allocated version number and normalized intervals. No update method exists on
version entities.

`StoreScheduleState` methods:

```java
public long allocateOperatingVersion() {
    return nextOperatingVersion++;
}

public void activateOperating(long versionId) {
    activeOperatingScheduleVersionId = versionId;
}
```

Provide equivalent reservation methods and getters for active IDs.

- [ ] **Step 5: Implement locking repositories**

State initialization is MySQL-safe:

```java
@Modifying
@Query(value = """
        INSERT IGNORE INTO store_schedule_state
            (store_id, next_operating_version, next_reservation_version,
             created_at, updated_at)
        VALUES (:storeId, 1, 1, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
        """, nativeQuery = true)
void initialize(@Param("storeId") long storeId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select state from StoreScheduleState state where state.storeId = :storeId")
Optional<StoreScheduleState> findForUpdateByStoreId(@Param("storeId") long storeId);
```

- [ ] **Step 6: Run repository tests and verify GREEN**

Run the focused repository command from Step 2. Expected: PASS with zero skipped
tests.

- [ ] **Step 7: Commit Task 2**

```powershell
git add backend/src/main/resources/db/migration/V9__create_store_schedule_versions.sql backend/src/main/java/com/miriyum/domain/store/schedule backend/src/test/java/com/miriyum/domain/store/schedule/repository
git commit -m "feat(store): persist immutable schedule versions"
```

---

### Task 3: Canonical Fingerprints and Response Mapping

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/OperatingHoursResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/dto/ReservationTimeSlotsResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleFingerprint.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/dto/StoreScheduleResponseTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleFingerprintTest.java`

**Interfaces:**
- Consumes: persisted version aggregates and weekly request records
- Produces:
  - `OperatingHoursResponse.from(OperatingScheduleVersion)`
  - `ReservationTimeSlotsResponse.from(ReservationScheduleVersion)`
  - `forOperating(long storeId, WeeklyOperatingHoursRequest request)`
  - `forReservation(long storeId, WeeklyReservationTimeSlotsRequest request)`

- [ ] **Step 1: Write failing response and fingerprint tests**

Assert responses always return Monday through Sunday and preserve overnight local
times. Assert fingerprints:

```java
assertThat(forOperating(storeId, requestA))
        .isEqualTo(forOperating(storeId, sameContentDifferentArrayOrder));
assertThat(forOperating(storeId, requestA))
        .isNotEqualTo(forOperating(storeId, changedEndTime));
assertThat(forOperating(7L, requestA))
        .isNotEqualTo(forOperating(8L, requestA));
```

- [ ] **Step 2: Run Task 3 tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.dto.StoreScheduleResponseTest" --tests "com.miriyum.domain.store.schedule.service.StoreScheduleFingerprintTest"
```

Expected: compilation fails because response and fingerprint types do not exist.

- [ ] **Step 3: Implement canonical mapping and fingerprints**

Responses are records with `long version` and the appropriate seven-day list.
Fingerprint canonicalization sorts by `DayOfWeek.getValue`, then start time, end time,
and interval kind. Use length-prefixed fields and `RequestFingerprint.of(...)`, not
plain delimiter joining.

Route identities:

```text
PUT|/api/v1/store-operator/stores/{storeId}/operating-hours
PUT|/api/v1/store-operator/stores/{storeId}/reservation-time-slots
```

- [ ] **Step 4: Run Task 3 tests and verify GREEN**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule backend/src/test/java/com/miriyum/domain/store/schedule
git commit -m "feat(store): define schedule write contracts"
```

---

### Task 4: Transactional Idempotent Publication Service

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/service/ScheduleCommandResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleServiceTest.java`

**Interfaces:**
- Consumes: #33 `StoreService`, repositories, policy, #32 idempotency executor
- Produces:

```java
ScheduleCommandResult<OperatingHoursResponse> replaceOperatingHours(
        long operatorId, long storeId, IdempotencyKey key,
        WeeklyOperatingHoursRequest request);

ScheduleCommandResult<ReservationTimeSlotsResponse> replaceReservationTimeSlots(
        long operatorId, long storeId, IdempotencyKey key,
        WeeklyReservationTimeSlotsRequest request);
```

- [ ] **Step 1: Write failing service tests**

Test observable results and captured business results:

```java
@Test void operatingPublicationUsesCentralAuthorityAndReturnsVersionOne();
@Test void secondOperatingPublicationReturnsVersionTwoAndKeepsHistory();
@Test void reservationPublicationReferencesCurrentOperatingVersion();
@Test void reservationPublicationWithoutOperatingScheduleReturnsStore006();
@Test void closedStoreReturnsStore005();
@Test void temporarilyClosedStoreCanPublish();
@Test void createCommandsUseDistinctCommandTypesAndCanonicalFingerprints();
@Test void unrelatedPersistenceFailureIsNotHidden();
```

Stub `IdempotencyExecutor.execute` by invoking the supplied `BusinessResult` callback,
matching the existing `StoreServiceTest` pattern.

- [ ] **Step 2: Run service tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.service.StoreScheduleServiceTest"
```

Expected: compilation fails because publication service types do not exist.

- [ ] **Step 3: Implement publication service**

Annotate both methods:

```java
@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
```

Before idempotency execution, call central authority and reject:

```java
StoreManagementView authority =
        storeService.requireManagementAuthority(operatorId, storeId);
if (authority.verificationStatus() != VerificationStatus.APPROVED) {
    throw new ServiceException(StoreErrorCode.VERIFICATION_STATE_CONFLICT);
}
if (authority.operationStatus() == OperationStatus.CLOSED) {
    throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
}
```

Inside the idempotent callback:

1. `stateRepository.initialize(storeId)`
2. load the state row with `findForUpdateByStoreId`
3. validate the complete weekly request
4. allocate the next stream version
5. persist and flush the immutable version
6. update the active pointer
7. return `BusinessResult` with HTTP 200, response code `SUCCESS`, resource type
   `STORE_SCHEDULE`, resource ID `<storeId>:<stream>:<version>`, and response DTO

Reservation publication loads the state row's active operating version, fails with
`STORE_006` when absent, and passes its entries to `validateReservation`.

Convert replayed `JsonNode` data with the injected Jackson `ObjectMapper`.

- [ ] **Step 4: Run service tests and verify GREEN**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit Task 4**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleServiceTest.java
git commit -m "feat(store): publish schedules idempotently"
```

---

### Task 5: Store-Operator HTTP Endpoints

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/schedule/controller/StoreScheduleController.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/schedule/controller/StoreScheduleControllerTest.java`

**Interfaces:**
- Consumes: `StoreScheduleService`, `AuthenticatedPrincipal`, `IdempotencyKey`
- Produces: both Issue #34 PUT endpoints

- [ ] **Step 1: Write failing MockMvc tests**

Use `@WebMvcTest(StoreScheduleController.class)`, import
`StoreManagementSecurityConfig` and `GlobalExceptionHandler`, and mock
`StoreScheduleService` plus `JwtTokenProvider`.

Test:

```java
@Test void missingBearerTokenReturnsAuth001();
@Test void consumerTokenReturnsAuth004();
@Test void missingIdempotencyKeyReturnsCommon003();
@Test void invalidWeeklyShapeReturnsCommon001();
@Test void operatingConflictReturnsStore006();
@Test void otherOwnerReturnsStore003();
@Test void replaceOperatingReturnsVersionEnvelope();
@Test void replaceReservationReturnsVersionEnvelope();
```

- [ ] **Step 2: Run controller tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.controller.StoreScheduleControllerTest"
```

Expected: compilation fails because the controller does not exist.

- [ ] **Step 3: Implement controller**

Both methods parse the optional raw header only through `IdempotencyKey.parse`:

```java
@PutMapping("/{storeId}/operating-hours")
public ResponseEntity<ApiResponse<OperatingHoursResponse>> replaceOperatingHours(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable long storeId,
        @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
        @Valid @RequestBody WeeklyOperatingHoursRequest request)

@PutMapping("/{storeId}/reservation-time-slots")
public ResponseEntity<ApiResponse<ReservationTimeSlotsResponse>> replaceReservationTimeSlots(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable long storeId,
        @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
        @Valid @RequestBody WeeklyReservationTimeSlotsRequest request)
```

Use the service result's HTTP status and common `ApiResponse.success`. Do not create a
new filter chain: #33's `/api/v1/store-operator/stores/**` chain already owns both
paths.

- [ ] **Step 4: Run controller tests and verify GREEN**

Run the command from Step 2. Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule/controller backend/src/test/java/com/miriyum/domain/store/schedule/controller
git commit -m "feat(store): expose schedule publication API"
```

---

### Task 6: Concurrency, Rollback, and Full Verification

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/repository/StoreScheduleRepositoryIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreSchedulePublicationIT.java`

**Interfaces:**
- Consumes: complete Issue #34 implementation
- Produces: executable MySQL evidence for atomicity and concurrency

- [ ] **Step 1: Add concurrent publication integration test**

Create a store and initial state. Launch two independent `TransactionTemplate`
executions behind a `CountDownLatch`. Each publishes different operating content with
a different idempotency key. Assert:

```java
assertThat(versionRepository.findAllByStoreIdOrderByVersionNumber(storeId))
        .extracting(OperatingScheduleVersion::getVersionNumber)
        .containsExactly(1L, 2L);
assertThat(stateRepository.findById(storeId).orElseThrow()
        .getActiveOperatingScheduleVersionId()).isNotNull();
```

The active pointer must reference version 2, and no duplicate version number may
exist.

- [ ] **Step 2: Add atomic rollback integration test**

Within one transaction, execute the idempotency command and persist/activate a
schedule, then throw `IllegalStateException("force rollback")`. Assert zero matching
version rows and zero matching `idempotency_commands` rows.

- [ ] **Step 3: Run all Issue #34 focused tests**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.*"
```

Expected: all schedule unit, MVC, service, and Testcontainers tests pass with zero
skipped tests.

- [ ] **Step 4: Run complete backend verification**

Run:

```powershell
.\gradlew.bat clean build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Check scope and diff hygiene**

Run from the worktree root:

```powershell
git diff --check codex/33-store-core...HEAD
git diff --name-status codex/33-store-core...HEAD
git log --oneline codex/33-store-core..HEAD
```

Expected: only Issue #34 schedule code, V9 migration, tests, and design/plan documents.
No menu, frontend, identity-stub, reservation-capacity, or existing-reservation
changes.

- [ ] **Step 6: Commit verification additions**

```powershell
git add backend/src/test/java/com/miriyum/domain/store/schedule
git commit -m "test(store): verify schedule publication atomicity"
```

- [ ] **Step 7: Record PR evidence**

Record:

```text
Focused schedule test count, failures, and skipped count
Testcontainers MySQL image
Full clean build result and total test count
git diff --check result
Issue #34 file allowlist
Issue #33 stacked base and merge status
```

Do not claim Issue #35 or frontend completion in the Issue #34 PR.
