# Reservation Cancellation Partial Capacity Recovery Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Allow consumer and store-operator cancellation after a newer capacity publication leaves zero or partial overlapping buckets, while preserving original allocation, corruption, locking, rollback, audit, MenuHold, and replay invariants.

**Architecture:** Keep the original allocation set as the authoritative creation ledger and continue requiring exact reservation-window coverage for it. Treat the newer current set as derived publication occupancy: allow it to be empty or partial, but require every returned bucket to match store/date/latest-version and the same half-open overlap predicate used by publication. Preserve the existing original/current ID union, PK-ascending lock, latest-version recheck, and one restore per deduplicated bucket.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Gradle, JUnit 5, AssertJ, Mockito, Testcontainers MySQL 8.0.40.

## Global Constraints

- Issue #214 exact allowlist is the only writable repository scope.
- Public API, OpenAPI, response, error-code, and database schema contracts do not change.
- The lock order remains idempotency -> Reservation -> MenuHold root -> capacity bucket union by PK ascending.
- Same-version current IDs must still equal original allocation IDs exactly.
- Original allocation buckets must still cover the full reservation window continuously.
- Newer current buckets may be empty or partial, but every present bucket must overlap the reservation window and match store/date/latest version.
- Latest-version recheck and `ReservationCapacityBucket.restore()` underflow rollback remain mandatory.
- Production code must not be written until the corresponding regression test has failed for the expected current behavior.

---

### Task 1: Separate original coverage from newer current overlap validation

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java:2444-2568`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java:533-765`

**Interfaces:**
- Consumes: `findLatestPolicyBucketIdsOverlapping(...)`, `findAllByIdInForUpdate(...)`, `CancellationCapacityWindow`, and the existing original/current bucket union.
- Produces: original validation with exact coverage and newer-current validation with per-bucket half-open overlap.

- [ ] **Step 1: Add a failing unit test for newer empty current IDs**

Add a focused test that stubs a version-3 original allocation and version 4 as latest, returns an empty current ID list, locks only the original bucket, and expects cancellation success plus one restore of the original occupancy.

```java
@Test
@DisplayName("새 최신 정책에 겹치는 버킷이 없으면 원본 점유만 복구해 취소한다")
void cancellationAllowsEmptyCurrentBucketsForNewerPolicy() {
    IdempotencyCommand command = cancellationCommand("consumer", 11L);
    CancellationFixture fixture = stubSuccessfulCancellation(
            command, MenuHoldTerminationPresence.NO_HOLD);
    ReservationCapacityBucket firstOriginal = fixture.lockedBuckets().get(0);
    ReservationCapacityBucket secondOriginal = fixture.lockedBuckets().get(1);

    given(capacityBucketRepository.findLatestPolicyVersion(22L, SERVICE_DATE))
            .willReturn(Optional.of(4L));
    given(capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
            List.of(22L), SERVICE_DATE, START_TIME, LocalTime.of(19, 15)))
            .willReturn(List.of());
    given(capacityBucketRepository.findAllByIdInForUpdate(List.of(301L, 302L)))
            .willReturn(List.of(firstOriginal, secondOriginal));

    Throwable failure = catchThrowable(() -> invokeConsumerCancellation(
            command, null, NOW.minusSeconds(10), CONSUMER_CANCELLATION_CORRELATION));

    assertThat(failure).isNull();
    assertThat(fixture.reservation().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    assertThat(List.of(firstOriginal, secondOriginal)).allSatisfy(bucket -> {
        assertThat(bucket.getOccupiedPeople()).isEqualTo(3);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
    });
}
```

- [ ] **Step 2: Run the empty-current test and verify RED**

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationAllowsEmptyCurrentBucketsForNewerPolicy"
```

Expected: FAIL because `validateObservedCurrentBucketIds()` throws `CAPACITY_POLICY_CHANGED` for the empty list.

- [ ] **Step 3: Add a failing unit test for partial current coverage**

Add a test with original `301` covering the whole window and current `401` covering only the middle of the window. Expect both buckets to be restored and cancellation to succeed.

```java
@Test
@DisplayName("새 최신 정책이 예약 구간 일부만 덮으면 실제 겹치는 점유만 함께 복구한다")
void cancellationAllowsPartialCurrentCoverageForNewerPolicy() {
    IdempotencyCommand command = cancellationCommand("consumer", 11L);
    CancellationFixture fixture = stubSuccessfulCancellation(
            command, MenuHoldTerminationPresence.NO_HOLD);
    ReservationCapacityBucket firstOriginal = fixture.lockedBuckets().get(0);
    ReservationCapacityBucket secondOriginal = fixture.lockedBuckets().get(1);
    ReservationCapacityBucket current = cancellationBucket(
            401L, LocalTime.of(18, 15), LocalTime.of(18, 45), 6, 2, 4L);

    given(capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
            List.of(22L), SERVICE_DATE, START_TIME, LocalTime.of(19, 15)))
            .willReturn(List.of(401L));
    given(capacityBucketRepository.findAllByIdInForUpdate(
            List.of(301L, 302L, 401L)))
            .willReturn(List.of(firstOriginal, secondOriginal, current));

    Throwable failure = catchThrowable(() -> invokeConsumerCancellation(
            command, null, NOW.minusSeconds(10), CONSUMER_CANCELLATION_CORRELATION));

    assertThat(failure).isNull();
    assertThat(fixture.reservation().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
    assertThat(List.of(firstOriginal, secondOriginal, current)).allSatisfy(bucket -> {
        assertThat(bucket.getOccupiedPeople()).isEqualTo(3);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
    });
}
```

- [ ] **Step 4: Run the partial-current test and verify RED**

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationAllowsPartialCurrentCoverageForNewerPolicy"
```

Expected: FAIL because `validateCancellationCoverage()` rejects the gap before and after current bucket `401`.

- [ ] **Step 5: Implement separate original and current validation**

Change `validateObservedCurrentBucketIds()` so `null` remains invalid but an empty list normalizes to an empty `TreeSet`. Keep the existing same-version ID equality check in `cancelReservationWork()`.

Replace the single full-coverage validator with a common metadata loader plus two explicit callers:

```java
private static List<ReservationCapacityBucket> validateLockedBucketMetadata(
        Map<Long, ReservationCapacityBucket> lockedById,
        Set<Long> expectedIds,
        long storeId,
        CancellationCapacityWindow window,
        long expectedVersion
) {
    List<ReservationCapacityBucket> buckets = expectedIds.stream()
            .map(lockedById::get)
            .toList();
    for (ReservationCapacityBucket bucket : buckets) {
        if (!matchesCancellationBucket(
                bucket, storeId, window.serviceDate(), expectedVersion)) {
            throw capacityPolicyChanged();
        }
    }
    return buckets;
}

private static void validateLockedOriginalBucketSet(
        Map<Long, ReservationCapacityBucket> lockedById,
        Set<Long> expectedIds,
        long storeId,
        CancellationCapacityWindow window,
        long expectedVersion
) {
    List<ReservationCapacityBucket> buckets = validateLockedBucketMetadata(
            lockedById, expectedIds, storeId, window, expectedVersion);
    validateCancellationCoverage(buckets, window);
}

private static void validateLockedCurrentBucketSet(
        Map<Long, ReservationCapacityBucket> lockedById,
        Set<Long> expectedIds,
        long storeId,
        CancellationCapacityWindow window,
        long expectedVersion
) {
    List<ReservationCapacityBucket> buckets = validateLockedBucketMetadata(
            lockedById, expectedIds, storeId, window, expectedVersion);
    for (ReservationCapacityBucket bucket : buckets) {
        if (!bucket.getStartTime().isBefore(window.occupancyEndTime())
                || !bucket.getEndTime().isAfter(window.startTime())) {
            throw capacityPolicyChanged();
        }
    }
}
```

Call original validation unconditionally. Call current validation only when `latestPolicyVersion > capacityPolicyVersion`; an empty current set is valid in that branch.

- [ ] **Step 6: Preserve corruption tests and add current non-overlap coverage**

Remove `current-empty`, `current-coverage-gap`, and `current-coverage-overlap` from `cancellationCapacityRejectsInvalidLatestCurrentAndLockedContracts`; current validation is intentionally per bucket and does not reintroduce a whole-set continuity or mutual-overlap invariant. Add `current-non-overlap`, using a latest-version bucket outside the reservation window, and keep the expected `CAPACITY_POLICY_CHANGED` result.

```java
case "current-non-overlap" -> {
    current = cancellationBucket(
            401L, LocalTime.of(10, 0), LocalTime.of(11, 0), 6, 2, 4L);
    lockedBuckets = List.of(original, current);
}
```

Keep `same-version-id-mismatch`, wrong store/date/version, locked union corruption, version race, original coverage, and underflow cases unchanged.

- [ ] **Step 7: Run the focused service tests and verify GREEN**

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationAllowsEmptyCurrentBucketsForNewerPolicy" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationAllowsPartialCurrentCoverageForNewerPolicy" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationCapacityRejectsInvalidLatestCurrentAndLockedContracts" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationCapacityRejectsCorruptAllocationBucketAndVersionRace"
```

Expected: PASS with no failures, errors, or skips.

- [ ] **Step 8: Commit the unit-backed production change**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java
git commit -m "fix(reservation): allow partial latest capacity recovery"
```

---

### Task 2: Prove actual publication-to-cancellation behavior in MySQL

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationIT.java:296-316,611-745`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCapacityPublicationIT.java:90-180,740-806`

**Interfaces:**
- Consumes: the production `ReservationCapacityCommandFacade`, `ReservationCancellationCommandFacade`, real repositories, MySQL row locks, audit, idempotency, and MenuHold transaction participants.
- Produces: end-to-end regression evidence for disjoint and partial capacity publications.

- [ ] **Step 1: Add disjoint and partial cancellation integration fixtures**

In `ReservationCancellationIT`, add a helper that creates a newer capacity version without allocation rows and materializes occupancy only when the supplied interval overlaps the reservation window.

```java
private List<Long> seedPublishedCapacityVersion(
        long storeId,
        long policyVersion,
        List<PublishedBucket> intervals,
        int occupiedPeople,
        int occupiedTeams
) {
    return intervals.stream()
            .map(interval -> capacityBucketRepository.saveAndFlush(
                    ReservationCapacityBucket.create(
                            storeId, SERVICE_DATE,
                            interval.startTime(), interval.endTime(),
                            10, 5,
                            interval.overlapsReservation() ? occupiedPeople : 0,
                            interval.overlapsReservation() ? occupiedTeams : 0,
                            1, 10, true, policyVersion)))
            .map(ReservationCapacityBucket::getId)
            .toList();
}

private record PublishedBucket(
        LocalTime startTime,
        LocalTime endTime,
        boolean overlapsReservation
) {
}
```

Use explicit interval values in each test so the helper does not infer policy semantics from production code.

- [ ] **Step 2: Add consumer disjoint-publication + MenuHold integration test**

Create a `confirmedScenario(true, false, 2)`, publish a newer bucket completely outside the reservation window with zero occupancy, cancel by consumer, then assert:

```java
assertThat(result.httpStatus()).isEqualTo(200);
assertCommittedResourceEffect(scenario);
disjointLatestIds.forEach(id -> assertCapacity(id, 0, 0));
```

- [ ] **Step 3: Add store-operator partial-publication integration test**

Create a no-menu confirmed scenario, seed one newer bucket that overlaps only the middle of the reservation window with `PARTY_SIZE` people and one team plus one disjoint zero-occupancy bucket, cancel by store operator, and assert the reservation is `CANCELLED`, `auditCount(scenario)` is one, every original bucket and the overlapping latest bucket become zero, the disjoint bucket remains zero, and `menuHoldStatus(scenario)` is null.

- [ ] **Step 4: Run the cancellation integration class**

Run:

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT"
```

Expected: every `ReservationCancellationIT` test passes against MySQL 8.0.40.

- [ ] **Step 5: Add an actual disjoint replace-then-cancel integration test**

In `ReservationCapacityPublicationIT`:

- Autowire `ReservationCancellationCommandFacade`.
- Delete `reservation_cancellation_audits` before deleting reservations in cleanup.
- Use the existing store, consumer, reservation, original allocation, mocked accepting interval validation, and real capacity publication facade.
- Replace capacity with one bucket disjoint from the reservation.
- Read the reservation's consumer account ID and invoke real consumer cancellation.
- Assert publication version increment, latest occupancy remains zero, original occupancy becomes zero, reservation is `CANCELLED`, and one cancellation audit exists.

```java
@Test
void disjointPublicationThenConsumerCancellationRestoresOnlyOriginalOccupancy() {
    OwnerStore owner = createStore("capacity-disjoint-cancel@example.com", "1234567894");
    long reservationId = seedConsumerAndReservation(owner.storeId());
    ReservationCapacityBucket original = seedOriginalCapacityAllocation(
            owner.storeId(), reservationId);
    acceptEveryStoreInterval();

    ReservationCapacityCommandResult publication = commandFacade.replace(
            owner.operatorId(), owner.storeId(), SERVICE_DATE, key(40),
            disjointRequest());
    long consumerId = reservationRepository.findById(reservationId)
            .orElseThrow().getConsumerAccountId();
    ReservationCancellationCommandResult cancellation = cancellationFacade.cancelByConsumer(
            consumerId, reservationId, key(41),
            new ConsumerCancellationRequest("disjoint publication"));

    assertThat(publication.data().buckets()).singleElement()
            .satisfies(bucket -> assertThat(bucket.occupiedPeople()).isZero());
    assertThat(cancellation.httpStatus()).isEqualTo(200);
    assertThat(reservationRepository.findById(reservationId).orElseThrow().getStatus())
            .isEqualTo(ReservationStatus.CANCELLED);
    assertThat(capacityBucketRepository.findById(original.getId()).orElseThrow()
            .getOccupiedPeople()).isZero();
}
```

- [ ] **Step 6: Run the publication integration class**

Run:

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.service.ReservationCapacityPublicationIT"
```

Expected: all publication, concurrency, and new replace-then-cancel tests pass.

- [ ] **Step 7: Commit the MySQL regression coverage**

```powershell
git add -- backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationIT.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCapacityPublicationIT.java
git commit -m "test(reservation): cover cancellation after capacity republish"
```

---

### Task 3: Verify the complete backend and exact scope

**Files:**
- Verify: all six paths in Issue #214 exact allowlist

**Interfaces:**
- Consumes: Task 1 production behavior and Task 2 MySQL regression tests.
- Produces: reproducible completion evidence for Issue #214 and the follow-up pull request.

- [ ] **Step 1: Run focused unit and integration verification again**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest" --tests "com.miriyum.domain.reservation.service.ReservationCapacityPublicationServiceTest"
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT" --tests "com.miriyum.domain.reservation.service.ReservationCapacityPublicationIT"
```

Expected: all selected tests pass with zero failures, errors, and skips.

- [ ] **Step 2: Run the complete backend build from a clean execution**

```powershell
backend\gradlew.bat -p backend build --rerun-tasks
```

Expected: `BUILD SUCCESSFUL`; unit/slice and Testcontainers integration suites complete with zero failures, errors, and skips.

- [ ] **Step 3: Verify diff hygiene and Issue allowlist**

```powershell
git diff --check origin/dev...HEAD
git status --short
git diff --name-only origin/dev...HEAD
```

Expected changed paths only:

```text
docs/superpowers/specs/2026-08-10-reservation-cancellation-partial-capacity-recovery-design.md
docs/superpowers/plans/2026-08-10-reservation-cancellation-partial-capacity-recovery.md
backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java
backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java
backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationIT.java
backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCapacityPublicationIT.java
```

- [ ] **Step 4: Record the final evidence**

Record the exact commands, exit codes, test counts, changed-path allowlist result, and remaining risk in the pull request. Do not amend applied migrations, delete worktrees, or include files outside Issue #214.
