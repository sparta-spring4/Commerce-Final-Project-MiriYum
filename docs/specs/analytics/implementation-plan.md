# Store Dashboard Analytics Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build Issue #270's store-operator dashboard API with six independently complete metrics, a shared KST `asOf`, source-owned public query contracts, durable MySQL snapshots, and a measured operator-confirmed Reservation no-show cell from merged #240 while keeping unsupported candidate no-shows explicit.

**Architecture:** Store authenticates and returns a versioned authority DTO. Reservation and Waiting aggregate only their own tables behind public Service/DTO boundaries. Analytics captures one `asOf`, calls those contracts independently, maps failures per metric, and atomically publishes one snapshot header plus six metric cells; it never imports foreign Entity or Repository types.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA, MySQL 8, Flyway V54, JUnit 5, AssertJ, Testcontainers, Spring MVC tests, OpenAPI 3.1, Redocly CLI 2.35.1.

## Global Constraints

- Store sanctions V53 is on `dev`. #270 uses V54 under the explicit latest-`dev`-max-plus-one rule; verify it remains latest `dev` max + 1 before the final merge and verify the complete clean and upgrade paths.
- Do not consume Reservation or Waiting Entity/Repository classes from `com.miriyum.domain.analytics`.
- Capture one `Instant generatedAt`, truncate it to a UTC one-minute `asOf`, and require every source DTO and every output metric to contain that exact `asOf`.
- `value=0` is a measured zero; missing, delayed, quarantined, or invalid-denominator input uses null with a non-COMPLETE status and reason.
- Reservation no-show candidate remains `UNAVAILABLE/SOURCE_CONTRACT_MISSING`. The operator-confirmed cell is measured from #240's append-only audit contract; do not infer candidates or corrections that the producer does not publish.
- Foreign existing stores return `403 STORE_003`; actually missing stores return `404 STORE_001`.
- Run focused unit and affected MySQL integration classes locally. Leave the full backend suite to GitHub Backend CI.
- Make no frontend, Pro analytics, export, or personal-row changes.

---

### Task 1: Reserve V54 and version Store dashboard authority

**Files:**
- Create: `backend/src/main/resources/db/migration/V54__create_dashboard_analytics_snapshots.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/dto/contract/StoreDashboardAuthority.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/entity/Store.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/entity/StoreTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/repository/DashboardAnalyticsMigrationTest.java`

**Interfaces:**
- Produces: `StoreDashboardAuthority StoreService.requireDashboardAuthority(long operatorAccountId, long storeId)`.
- Produces: `StoreDashboardAuthority(long storeId, String timeZoneId, long dashboardAuthorityVersion)`.

- [ ] **Step 1: Write the failing Store authority tests**

```java
@Test
void dashboardAuthorityReturnsOwnedStoreKstAndVersion() {
    StoreDashboardAuthority authority = service.requireDashboardAuthority(41L, 17L);
    assertThat(authority).isEqualTo(new StoreDashboardAuthority(17L, "Asia/Seoul", 1L));
}

@Test
void dashboardAuthorityRejectsForeignExistingStore() {
    assertThatThrownBy(() -> service.requireDashboardAuthority(99L, 17L))
            .isInstanceOfSatisfying(ServiceException.class,
                    failure -> assertThat(failure.getErrorCode())
                            .isEqualTo(StoreErrorCode.ACCESS_DENIED));
}
```

- [ ] **Step 2: Run the authority tests and verify RED**

Run:

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.entity.StoreTest --tests com.miriyum.domain.store.service.StoreServiceTest --console=plain
```

Expected: compile failure because `StoreDashboardAuthority` and `requireDashboardAuthority` do not exist.

- [ ] **Step 3: Add the migration contract test before SQL**

```java
@Test
void v54CreatesAuthorityVersionAndAtomicMetricKeys() {
    assertThat(column("stores", "dashboard_authority_version")).isNotNull();
    assertThat(uniqueColumns("dashboard_analytics_snapshots"))
            .containsExactly("store_id", "business_date", "as_of", "store_authority_version");
    assertThat(uniqueColumns("dashboard_analytics_metric_snapshots"))
            .containsExactly("dashboard_snapshot_id", "metric_key");
}
```

- [ ] **Step 4: Run the migration test and verify RED**

Run:

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.analytics.repository.DashboardAnalyticsMigrationTest --console=plain
```

Expected: FAIL because V54 and its tables/column do not exist.

- [ ] **Step 5: Implement V54 and the authority contract**

Add `stores.dashboard_authority_version BIGINT NOT NULL DEFAULT 1` with a positive check and `reservation_capacity_buckets.policy_published_at` as the durable capacity-policy effectivity boundary. Create `dashboard_analytics_snapshots` and `dashboard_analytics_metric_snapshots` with the exact unique keys above, FK metric rows to the header with `ON DELETE CASCADE`, CHECK constraints for versions and enum strings, JSON metric payload, nullable source fields, and no consumer/reservation/waiting identifiers.

```java
public record StoreDashboardAuthority(
        long storeId,
        String timeZoneId,
        long dashboardAuthorityVersion
) {
    public StoreDashboardAuthority {
        if (storeId <= 0 || dashboardAuthorityVersion <= 0
                || !"Asia/Seoul".equals(timeZoneId)) {
            throw new IllegalArgumentException("valid KST dashboard authority is required");
        }
    }
}
```

`requireDashboardAuthority` must call the existing account and ownership checks, distinguish `STORE_003` from `STORE_001`, and return only the public DTO.

- [ ] **Step 6: Run focused Store and migration tests and verify GREEN**

Run the two commands from Steps 2 and 4. Expected: PASS.

- [ ] **Step 7: Commit the authority and schema slice**

```powershell
git add -- backend/src/main/resources/db/migration/V54__create_dashboard_analytics_snapshots.sql backend/src/main/java/com/miriyum/domain/store backend/src/test/java/com/miriyum/domain/store backend/src/test/java/com/miriyum/domain/analytics/repository/DashboardAnalyticsMigrationTest.java
git commit -m "feat(analytics): version dashboard authority and snapshot schema"
```

### Task 2: Publish Reservation's analytics read contract

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/dto/contract/ReservationAnalyticsSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCancellationAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationFulfillmentAuditRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceIT.java`

**Interfaces:**
- Consumes: `storeId`, KST `businessDate`, shared `asOf`.
- Produces: `ReservationAnalyticsSnapshot getDashboardSnapshot(long storeId, LocalDate businessDate, Instant asOf)`.

Use this exact public DTO shape:

```java
public record ReservationAnalyticsSnapshot(
        long storeId,
        LocalDate businessDate,
        Instant asOf,
        long todayReservationTeams,
        long reservedPeopleUnits,
        long offeredPeopleUnits,
        long reservedTeamUnits,
        long offeredTeamUnits,
        long cancelledTeams,
        long everConfirmedTeams,
        String inputCheckpoint,
        Instant dataThrough,
        long sourceVersion,
        boolean corrected
) {}
```

- [ ] **Step 1: Write failing unit tests for formulas and exclusions**

```java
@Test
void aggregatesDistinctConfirmedLifecycleTeamsAndExcludesFailedHolds() {
    ReservationAnalyticsSnapshot result = service.getDashboardSnapshot(
            17L, LocalDate.of(2026, 8, 16), Instant.parse("2026-08-16T09:00:00Z"));

    assertThat(result.todayReservationTeams()).isEqualTo(12);
    assertThat(result.reservedPeopleUnits()).isEqualTo(48);
    assertThat(result.offeredPeopleUnits()).isEqualTo(80);
    assertThat(result.cancelledTeams()).isEqualTo(2);
    assertThat(result.everConfirmedTeams()).isEqualTo(14);
}
```

Fixtures must include one duplicated technical audit, one cancelled confirmed reservation, one fulfilled reservation, and one failed/unconfirmed Hold. Expected literals must be hand-calculated.

- [ ] **Step 2: Run the unit test and verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceTest --console=plain
```

Expected: compile failure because the public contract does not exist.

- [ ] **Step 3: Add a failing MySQL correction/rebuild test**

```java
@Test
void duplicateAndCorrectedInputsConvergeToFullReaggregation() {
    ReservationAnalyticsSnapshot incremental = applyDuplicateAndCorrectionThenRead();
    ReservationAnalyticsSnapshot rebuilt = rebuildFromAuthoritativeRowsThenRead();

    assertThat(incremental.todayReservationTeams())
            .isEqualTo(rebuilt.todayReservationTeams());
    assertThat(incremental.cancelledTeams()).isEqualTo(rebuilt.cancelledTeams());
    assertThat(incremental.inputCheckpoint()).isEqualTo(rebuilt.inputCheckpoint());
}
```

- [ ] **Step 4: Run the integration test and verify RED**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceIT --console=plain
```

Expected: compile failure because the service is missing.

- [ ] **Step 5: Implement the narrow source-owned aggregate queries**

Count distinct Reservation IDs. Reconstruct status at `asOf` from `createdAt`, cancellation audit/`cancelledAt`, and fulfillment audit/`fulfilledAt`; never count page items. Compute capacity numerators from Reservation allocations, not current occupied fields that may include active Holds. Compute denominators from the applicable capacity policy buckets. Form the checkpoint from stable maximum source versions/IDs without embedding consumer or reservation IDs in the public string.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run Steps 2 and 4. Expected: PASS.

- [ ] **Step 7: Commit the Reservation contract slice**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/reservation backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceTest.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceIT.java
git commit -m "feat(reservation): publish dashboard analytics snapshot"
```

### Task 3: Publish Waiting's analytics read contract

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingAnalyticsSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingAnalyticsQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingTeamRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingStatusEventRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingAnalyticsQueryServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingAnalyticsQueryServiceIT.java`

**Interfaces:**
- Produces: `WaitingAnalyticsSnapshot getDashboardSnapshot(long storeId, LocalDate businessDate, Instant asOf)`.

```java
public record WaitingAnalyticsSnapshot(
        long storeId,
        LocalDate businessDate,
        Instant asOf,
        long waitingTeams,
        long calledTeams,
        long waitingPeople,
        long calledPeople,
        Long longestWaitSeconds,
        long confirmedNoShowTeams,
        String inputCheckpoint,
        Instant dataThrough,
        long sourceVersion,
        boolean corrected
) {}
```

- [ ] **Step 1: Write the failing state-boundary unit test**

```java
@Test
void countsOnlyWaitingAndCalledAsCurrentQueueAndKeepsNoShowSeparate() {
    WaitingAnalyticsSnapshot result = service.getDashboardSnapshot(
            17L, LocalDate.of(2026, 8, 16), Instant.parse("2026-08-16T09:00:00Z"));

    assertThat(result.waitingTeams()).isEqualTo(3);
    assertThat(result.calledTeams()).isEqualTo(1);
    assertThat(result.waitingPeople()).isEqualTo(8);
    assertThat(result.calledPeople()).isEqualTo(2);
    assertThat(result.longestWaitSeconds()).isEqualTo(1260L);
    assertThat(result.confirmedNoShowTeams()).isEqualTo(2);
}
```

The fixture must also contain ARRIVED, CHECKED_IN, CANCELLED, CLOSED_BY_STORE and RESERVATION_CONVERTED rows that do not increase current queue counts.

- [ ] **Step 2: Run the unit test and verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.reservation.waiting.service.WaitingAnalyticsQueryServiceTest --console=plain
```

Expected: compile failure because the source contract is absent.

- [ ] **Step 3: Add a failing MySQL asOf/version test**

```java
@Test
void laterStatusEventsDoNotChangeAnEarlierAsOfSnapshot() {
    Instant asOf = Instant.parse("2026-08-16T09:00:00Z");
    insertCallAfter(asOf);

    WaitingAnalyticsSnapshot snapshot = service.getDashboardSnapshot(
            17L, LocalDate.of(2026, 8, 16), asOf);

    assertThat(snapshot.waitingTeams()).isEqualTo(1);
    assertThat(snapshot.calledTeams()).isZero();
}
```

- [ ] **Step 4: Run the integration test and verify RED**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.waiting.service.WaitingAnalyticsQueryServiceIT --console=plain
```

Expected: compile failure because the service is missing.

- [ ] **Step 5: Implement source-owned asOf projection**

Use Waiting status events and team versions to select the latest event at or before `asOf`; do not filter current rows alone. Use `businessDate` from the Waiting ledger, count distinct teams, sum party sizes by selected status, and derive longest wait from the minimum `createdAt` among selected WAITING/CALLED teams. Return no consumer IDs or team lists.

- [ ] **Step 6: Run focused tests and verify GREEN**

Run Steps 2 and 4. Expected: PASS.

- [ ] **Step 7: Commit the Waiting contract slice**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/reservation/waiting backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingAnalyticsQueryServiceTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingAnalyticsQueryServiceIT.java
git commit -m "feat(waiting): publish dashboard analytics snapshot"
```

### Task 4: Persist immutable dashboard snapshots

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/analytics/dto/DashboardAnalyticsContracts.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/entity/DashboardSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/entity/DashboardMetricSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/repository/DashboardSnapshotRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/repository/DashboardMetricSnapshotRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/service/DashboardSnapshotTransactionExecutor.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/entity/DashboardSnapshotTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/repository/DashboardSnapshotRepositoryIT.java`

**Interfaces:**
- Produces nested records matching every schema in `analytics/openapi.yaml`.
- Produces `DashboardSnapshotTransactionExecutor.publish(DashboardSnapshotDraft)`.

Define the public/service types in `DashboardAnalyticsContracts` with these exact names. `MetricMetadata` is copied into each JSON metric record during response mapping; it is not emitted as a separate JSON property.

```java
public enum MetricCompleteness { COMPLETE, DELAYED, PARTIAL, UNAVAILABLE }
public enum MetricReasonCode {
    SOURCE_CONTRACT_MISSING, SOURCE_DELAYED, SOURCE_FAILED,
    SOURCE_QUARANTINED, INVALID_DENOMINATOR
}

public record MetricMetadata(
        String definitionVersion, long aggregationVersion, Instant asOf,
        Instant dataThrough, String inputCheckpoint,
        MetricCompleteness completeness, boolean corrected,
        MetricReasonCode reasonCode) {}
public record RateValue(long numerator, long denominator, BigDecimal ratio) {}
public record WaitingValue(
        long waitingTeams, long calledTeams, long waitingPeople,
        long calledPeople, Long longestWaitSeconds) {}
public record CountMetricResponse(
        Long value, @JsonUnwrapped MetricMetadata metadata) {}
public record RateMetricResponse(
        RateValue value, @JsonUnwrapped MetricMetadata metadata) {}
public record WaitingMetricResponse(
        WaitingValue value, @JsonUnwrapped MetricMetadata metadata) {}
public record NoShowValue(
        CountMetricResponse reservationCandidate,
        CountMetricResponse reservationConfirmed,
        CountMetricResponse waitingConfirmed) {}
public record NoShowMetricResponse(
        NoShowValue value, @JsonUnwrapped MetricMetadata metadata) {}
public record DashboardMetricsResponse(
        CountMetricResponse todayReservationTeams,
        RateMetricResponse reservationRate,
        RateMetricResponse teamCapacityUsageRate,
        RateMetricResponse cancellationRate,
        WaitingMetricResponse waiting,
        NoShowMetricResponse noShow) {}
public record DashboardSnapshotResponse(
        UUID snapshotId, String storeId, LocalDate businessDate,
        String timeZoneId, Instant asOf, Instant generatedAt,
        long storeAuthorityVersion, DashboardMetricsResponse metrics) {}
public record DashboardMetricDraft(
        String metricKey, JsonNode value, MetricMetadata metadata) {}
public record DashboardSnapshotDraft(
        long storeId, LocalDate businessDate, String timeZoneId,
        Instant asOf, Instant generatedAt, long storeAuthorityVersion,
        List<DashboardMetricDraft> metrics) {}
```

- [ ] **Step 1: Write failing entity invariants**

```java
@Test
void snapshotRejectsMetricWithDifferentAsOf() {
    assertThatThrownBy(() -> DashboardSnapshot.create(
            headerAt("2026-08-16T09:00:00Z"),
            metricAt("2026-08-16T09:00:01Z")))
            .isInstanceOf(IllegalArgumentException.class);
}

@Test
void unavailableMetricRequiresNullValueAndReason() {
    assertThatThrownBy(() -> unavailableMetric("0", null))
            .isInstanceOf(IllegalArgumentException.class);
}
```

- [ ] **Step 2: Run entity tests and verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.analytics.entity.DashboardSnapshotTest --console=plain
```

Expected: compile failure because analytics entities do not exist.

- [ ] **Step 3: Write the failing atomic persistence test**

```java
@Test
void duplicatePublishReplaysOneHeaderAndExactlySixMetricCells() {
    DashboardSnapshotResponse first = executor.publish(draftWithSixCells);
    DashboardSnapshotResponse replay = executor.publish(draftWithSixCells);

    assertThat(replay.snapshotId()).isEqualTo(first.snapshotId());
    assertThat(snapshotRepository.count()).isEqualTo(1);
    assertThat(metricRepository.countByDashboardSnapshotId(first.snapshotId()))
            .isEqualTo(6);
}
```

- [ ] **Step 4: Run the repository integration test and verify RED**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.analytics.repository.DashboardSnapshotRepositoryIT --console=plain
```

Expected: compile failure because repositories and executor are absent.

- [ ] **Step 5: Implement immutable entities and one transaction publisher**

Require all six metric keys exactly once. Serialize only OpenAPI-approved value objects. Lock the stable Store row before checking or replacing the latest marker so concurrent first publishers serialize. Reuse the first stored canonical snapshot for the same one-minute identity. Keep retention outside that lock: an hourly job uses the `(generated_at, dashboard_snapshot_id)` range index and deletes at most ten 1,000-row batches in independent transactions; metric cells follow through `ON DELETE CASCADE`.

- [ ] **Step 6: Run focused entity/repository tests and verify GREEN**

Run Steps 2 and 4. Expected: PASS.

- [ ] **Step 7: Commit persistence**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/analytics backend/src/test/java/com/miriyum/domain/analytics/entity backend/src/test/java/com/miriyum/domain/analytics/repository
git commit -m "feat(analytics): persist immutable dashboard snapshots"
```

### Task 5: Compose independent metrics and preserve partial failures

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/analytics/service/AnalyticsMetricFailureClassifier.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsService.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/service/AnalyticsMetricFailureClassifierTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsServiceIT.java`

**Interfaces:**
- Consumes the Store authority and the two source services from Tasks 1-3.
- Produces `DashboardSnapshotResponse getDashboard(long operatorAccountId, long storeId)`.

- [ ] **Step 1: Write the failing same-asOf and partial-failure unit tests**

```java
@Test
void everySourceAndMetricUsesTheOneCapturedAsOf() {
    DashboardSnapshotResponse response = service.getDashboard(41L, 17L);

    assertThat(reservationRequestedAsOf.get()).isEqualTo(FIXED_AS_OF);
    assertThat(waitingRequestedAsOf.get()).isEqualTo(FIXED_AS_OF);
    assertThat(allMetricAsOf(response)).containsOnly(FIXED_AS_OF);
}

@Test
void reservationFailureDoesNotOverwriteWaitingSuccess() {
    reservationSourceFailsWithRetryableDatabaseError();
    waitingSourceReturns(waitingSnapshot(3, 1, 2));

    DashboardSnapshotResponse response = service.getDashboard(41L, 17L);

    assertThat(response.metrics().todayReservationTeams().completeness())
            .isEqualTo(UNAVAILABLE);
    assertThat(response.metrics().waiting().completeness()).isEqualTo(COMPLETE);
    assertThat(response.metrics().noShow().value().waitingConfirmed().value())
            .isEqualTo(2L);
}
```

- [ ] **Step 2: Run unit tests and verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.analytics.service.AnalyticsMetricFailureClassifierTest --tests com.miriyum.domain.analytics.service.StoreDashboardAnalyticsServiceTest --console=plain
```

Expected: compile failure because classifier and composer are missing.

- [ ] **Step 3: Implement the minimal classifier and composer**

Authorize before reading `Clock`. Do not catch `AUTH_*`, `STORE_*`, fatal JVM errors, or invalid source DTOs as ordinary metric failures. Map known lag, quarantine and retryable source failures to public metric reasons. Build both reservation no-show cells with null/UNAVAILABLE/SOURCE_CONTRACT_MISSING and map Waiting no-show independently. Publish the collected header and exactly six cells once.

- [ ] **Step 4: Add a failing MySQL correction version test**

```java
@Test
void correctedInputPublishesAReplacementAggregationVersion() {
    DashboardSnapshotResponse original = readAndPublish();
    applyReservationCorrection();
    DashboardSnapshotResponse corrected = readAndPublish();

    assertThat(corrected.snapshotId()).isNotEqualTo(original.snapshotId());
    assertThat(corrected.metrics().cancellationRate().aggregationVersion())
            .isGreaterThan(original.metrics().cancellationRate().aggregationVersion());
    assertThat(corrected.metrics().cancellationRate().corrected()).isTrue();
}
```

- [ ] **Step 5: Run the service integration test and verify RED**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.analytics.service.StoreDashboardAnalyticsServiceIT --console=plain
```

Expected: FAIL until replacement publication is implemented.

- [ ] **Step 6: Implement replacement publication and verify GREEN**

Implement monotonic aggregation versions per metric definition/store/date, keep the old snapshot immutable, and atomically mark the new snapshot as the latest published version. Run Steps 2 and 5. Expected: PASS.

- [ ] **Step 7: Commit composition**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/analytics/service backend/src/test/java/com/miriyum/domain/analytics/service
git commit -m "feat(analytics): compose partial dashboard snapshots"
```

### Task 6: Expose the authorized HTTP endpoint and activate runtime OpenAPI

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/analytics/config/AnalyticsSecurityConfig.java`
- Create: `backend/src/main/java/com/miriyum/domain/analytics/controller/storeoperator/StoreDashboardAnalyticsController.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/controller/StoreDashboardAnalyticsControllerTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/analytics/controller/StoreDashboardAnalyticsHttpIT.java`
- Modify: `docs/specs/analytics/openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/analytics/AnalyticsOpenApiContractTest.java`

**Interfaces:**
- Produces `GET /api/v1/store-operators/stores/{storeId}/dashboard-statistics`.
- Removes `x-miriyum-runtime-status` and `x-miriyum-owner-issue` only in the runtime PR.

- [ ] **Step 1: Write failing controller tests**

```java
@Test
void ownedStoreReturnsSnapshotWithPartialReservationNoShow() throws Exception {
    mockMvc.perform(get(PATH, "17").with(storeOperator(41L)))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.metrics.noShow.completeness").value("PARTIAL"))
            .andExpect(jsonPath("$.data.metrics.noShow.value.reservationConfirmed.value")
                    .value(nullValue()))
            .andExpect(jsonPath("$.data.metrics.noShow.value.waitingConfirmed.value").value(2));
}
```

- [ ] **Step 2: Run controller tests and verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsControllerTest --console=plain
```

Expected: 404 because no runtime route exists.

- [ ] **Step 3: Write failing HTTP authority tests**

```java
@Test
void foreignStoreIs403AndMissingStoreIs404WithoutMetricLeak() throws Exception {
    assertSafeError(foreignStoreRequest(), 403, "STORE_003");
    assertSafeError(missingStoreRequest(), 404, "STORE_001");
}
```

- [ ] **Step 4: Run the HTTP integration test and verify RED**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsHttpIT --console=plain
```

Expected: FAIL because route/security wiring is absent.

- [ ] **Step 5: Implement controller/security and switch the contract to runtime**

Read the authenticated store-operator account only from the established principal. Parse `storeId` with the common positive public ID contract. Return standard `ApiResponse`. Do not accept account IDs, dates or asOf from the browser. Remove the two contract-only extensions and change the OpenAPI contract test to assert they are absent.

- [ ] **Step 6: Run controller, HTTP and OpenAPI tests and verify GREEN**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsControllerTest --tests com.miriyum.domain.analytics.AnalyticsOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest --console=plain
.\gradlew.bat integrationTest --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsHttpIT --console=plain
```

Expected: PASS.

- [ ] **Step 7: Commit HTTP runtime**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/analytics/config backend/src/main/java/com/miriyum/domain/analytics/controller backend/src/test/java/com/miriyum/domain/analytics/controller backend/src/test/java/com/miriyum/domain/analytics/AnalyticsOpenApiContractTest.java docs/specs/analytics/openapi.yaml
git commit -m "feat(analytics): expose store dashboard statistics"
```

### Task 7: Verify boundaries, contract drift and the exact change set

**Files:**
- Modify: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- Verify every path in the approved runtime allowlist and no others.

- [ ] **Step 1: Run focused unit tests**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.entity.StoreTest --tests com.miriyum.domain.store.service.StoreServiceTest --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingAnalyticsQueryServiceTest --tests com.miriyum.domain.analytics.entity.DashboardSnapshotTest --tests com.miriyum.domain.analytics.service.AnalyticsMetricFailureClassifierTest --tests com.miriyum.domain.analytics.service.StoreDashboardAnalyticsServiceTest --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsControllerTest --tests com.miriyum.domain.analytics.AnalyticsOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest --tests com.miriyum.architecture.DomainPackageArchitectureTest --console=plain
```

Expected: PASS with no foreign Entity/Repository dependency.

- [ ] **Step 2: Run only affected MySQL integration classes**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.analytics.repository.DashboardAnalyticsMigrationTest --tests com.miriyum.domain.analytics.repository.DashboardSnapshotRepositoryIT --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceIT --tests com.miriyum.domain.reservation.waiting.service.WaitingAnalyticsQueryServiceIT --tests com.miriyum.domain.analytics.service.StoreDashboardAnalyticsServiceIT --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsHttpIT --console=plain
```

Expected: PASS on actual MySQL/Testcontainers.

- [ ] **Step 3: Run OpenAPI lint and bundle**

```powershell
cd ..
npx --yes @redocly/cli@2.35.1 lint analytics docs/specs/store-operator-openapi.yaml
npx --yes @redocly/cli@2.35.1 bundle analytics --output NUL
```

Expected: both commands exit 0.

- [ ] **Step 4: Verify allowlist and whitespace**

```powershell
git diff --name-only origin/dev...HEAD
git diff --check
```

Expected: every changed path appears in the approved Issue #270 allowlist and `git diff --check` exits 0.

- [ ] **Step 5: Record full-suite status honestly**

Do not run local `build`, `check`, full `integrationTest`, or all shards. Open a PR to `dev` and record GitHub Backend CI as `PENDING` until the exact commit's required `backend-ci` check completes.

- [ ] **Step 6: Commit any verification-only contract correction**

If and only if focused verification required an allowlisted correction:

```powershell
git add -- <exact-approved-paths>
git commit -m "test(analytics): align dashboard contracts"
```

Otherwise do not create an empty commit.

## Phase 2: merged #240 confirmed no-show integration

#240/PR #394 is merged. Keep `reservationCandidate` unavailable because the producer explicitly excludes six-hour candidates and automatic classification. Activate only operator-confirmed Reservation no-shows from the append-only audit ledger. No migration is required.

### Task 8: Lock the activated public contract in tests

**Files:**
- Modify: `docs/specs/analytics/openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/analytics/AnalyticsOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/analytics/controller/StoreDashboardAnalyticsControllerTest.java`

**Contract:**
- `reservationCandidate`: null, `UNAVAILABLE`, `SOURCE_CONTRACT_MISSING`.
- `reservationConfirmed`: measured non-negative count, `COMPLETE`, no reason.
- `waitingConfirmed`: independently measured as before.
- top-level `noShow`: `analytics-004-no-show-v2`, `PARTIAL`, `SOURCE_CONTRACT_MISSING` because the candidate source is absent.

- [x] **Step 1: Make the OpenAPI example assert the new state**

Use a hand-checkable example with `reservationConfirmed.value = 1`, `waitingConfirmed.value = 2`, and the candidate unavailable. Keep the top-level value object present and partial.

- [x] **Step 2: Change the OpenAPI and controller tests before runtime code**

```java
assertUnavailableBecauseContractMissing(map(categories.get("reservationCandidate")));
assertCompleteCount(map(categories.get("reservationConfirmed")), 1L);
assertCompleteCount(map(categories.get("waitingConfirmed")), 2L);
assertThat(noShow.get("definitionVersion")).isEqualTo("analytics-004-no-show-v2");
assertThat(noShow.get("completeness")).isEqualTo("PARTIAL");
```

The controller stub must return the same three-cell shape; it must not turn a missing candidate into zero.

- [x] **Step 3: Run the focused contract tests and verify RED**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.analytics.AnalyticsOpenApiContractTest --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsControllerTest --console=plain
```

Expected: at least the controller/runtime expectation fails because current composition still emits the Reservation confirmed cell as unavailable and uses `ANALYTICS-004-v1`.

### Task 9: Publish operator-confirmed no-shows from Reservation

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/dto/contract/ReservationAnalyticsSnapshot.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationNoShowAuditRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceIT.java`

**Public interface:** append `long confirmedNoShowTeams` to the source-owned DTO. Analytics receives only this DTO through the existing public service.

```java
public record ReservationAnalyticsSnapshot(
        long storeId,
        LocalDate businessDate,
        Instant asOf,
        long todayReservationTeams,
        long reservedPeopleUnits,
        long offeredPeopleUnits,
        long reservedTeamUnits,
        long offeredTeamUnits,
        long cancelledTeams,
        long everConfirmedTeams,
        long confirmedNoShowTeams,
        String inputCheckpoint,
        Instant dataThrough,
        long sourceVersion,
        boolean corrected
) {}
```

**Repository interface:** keep the foreign ledger private to Reservation and return only a scalar projection.

```java
interface ReservationNoShowAnalytics {
    Long getConfirmedNoShowTeams();
    Long getMaxAuditId();
    Long getDataThroughEpochMicros();
}

ReservationNoShowAnalytics aggregateDashboardNoShows(
        long storeId, LocalDate businessDate, Instant asOf);
```

The native MySQL query must join `reservation_no_show_audits` to `reservations` by Reservation ID, filter both the target `store_id` and `reservations.service_date`, and require `audit.occurred_at <= :asOf`. Count the unique audit rows guaranteed by #240's unique Reservation constraint. Return `COALESCE(MAX(a.reservation_no_show_audit_id), 0)` and maximum `occurred_at`.

- [x] **Step 1: Write the failing source unit tests**

Add a repository projection fixture and assert:

```java
assertThat(snapshot.confirmedNoShowTeams()).isEqualTo(2L);
assertThat(snapshot.sourceVersion()).isGreaterThanOrEqualTo(37L);
assertThat(snapshot.dataThrough()).isEqualTo(NO_SHOW_OCCURRED_AT);
assertThat(snapshot.corrected()).isFalse();
```

Also reject a negative confirmed count and a confirmed count greater than `everConfirmedTeams` in the DTO invariant tests.

- [x] **Step 2: Run the unit test and verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceTest --console=plain
```

Expected: compile failure because the DTO field, repository aggregate and service dependency do not exist.

- [x] **Step 3: Write the failing actual-MySQL `asOf` test**

Insert one no-show audit before `asOf`, one after `asOf` for the same KST business date, one for another store, and one for another service date. Assert the earlier snapshot counts only the first audit and a later snapshot counts both in-scope audits. Assert the later max audit ID changes sourceVersion/inputCheckpoint and the later occurred time advances dataThrough.

- [x] **Step 4: Run the integration class and verify RED**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceIT --console=plain
```

Expected: compile or assertion failure until the audit aggregate is wired.

- [x] **Step 5: Implement the minimal Reservation contract**

Inject `ReservationNoShowAuditRepository` into `ReservationAnalyticsQueryService` and execute the audit aggregate inside the existing repeatable-read transaction. Set `confirmedNoShowTeams` from the projection. Incorporate max audit ID into the stable Reservation source version and opaque checkpoint, and maximum audit occurrence into dataThrough. Preserve `corrected=false`; #240 publishes no correction meaning.

- [x] **Step 6: Run the focused source tests and verify GREEN**

Run Steps 2 and 4. Expected: PASS.

- [x] **Step 7: Commit the producer-owned contract slice**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/reservation/dto/contract/ReservationAnalyticsSnapshot.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationNoShowAuditRepository.java backend/src/main/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryService.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceTest.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationAnalyticsQueryServiceIT.java
git commit -m "feat(reservation): publish confirmed no-show analytics"
```

### Task 10: Compose Reservation and Waiting no-shows independently

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/analytics/controller/StoreDashboardAnalyticsControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/analytics/AnalyticsOpenApiContractTest.java`

**Composition:**
- Change `NO_SHOW_DEFINITION` to `analytics-004-no-show-v2` and use the active OpenAPI's source-specific definition strings for all three subcells.
- Pass both Reservation and Waiting results/failures into `noShowMetric`.
- Build three subcells separately: static unavailable candidate, Reservation confirmed from its source or its classified failure, and Waiting confirmed from its source or its classified failure.
- Compute top sourceVersion as the maximum available source version; dataThrough as the maximum available source data-through; corrected as logical OR.
- Compute the top checkpoint as a deterministic SHA-256 over a stable definition prefix plus the two opaque source checkpoints. Never expose or parse either producer checkpoint.
- Keep top completeness `PARTIAL/SOURCE_CONTRACT_MISSING` even when both confirmed sources are complete, because the candidate contract remains missing.

- [x] **Step 1: Write the failing happy-path and failure-isolation tests**

```java
@Test
void composesConfirmedSourcesWithoutPretendingCandidateExists() {
    DashboardSnapshotResponse response = service.getDashboard(OPERATOR_ID, STORE_ID);

    assertThat(response.metrics().noShow().value().reservationCandidate().value()).isNull();
    assertThat(response.metrics().noShow().value().reservationConfirmed().value()).isEqualTo(1L);
    assertThat(response.metrics().noShow().value().waitingConfirmed().value()).isEqualTo(2L);
    assertThat(response.metrics().noShow().completeness()).isEqualTo(PARTIAL);
}

@Test
void reservationFailureDoesNotHideWaitingConfirmed() { /* confirmed unavailable; waiting complete */ }

@Test
void waitingFailureDoesNotHideReservationConfirmed() { /* waiting unavailable; confirmed complete */ }
```

Also assert different Reservation and Waiting checkpoints produce a stable combined checkpoint, max aggregation version/dataThrough, and corrected OR.

- [x] **Step 2: Run the analytics unit tests and verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.analytics.service.StoreDashboardAnalyticsServiceTest --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsControllerTest --tests com.miriyum.domain.analytics.AnalyticsOpenApiContractTest --console=plain
```

Expected: failures from the unmeasured Reservation confirmed cell and v1 definition.

- [x] **Step 3: Implement only the independent no-show composition**

Reuse the existing failure classifier and metadata constructors. Do not catch authorization/store failures and do not import Reservation entities or repositories into Analytics. Persist the unchanged six top-level metric keys; the no-show JSON value contains the three subcells.

- [x] **Step 4: Run focused runtime and boundary tests and verify GREEN**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.analytics.service.StoreDashboardAnalyticsServiceTest --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsControllerTest --tests com.miriyum.domain.analytics.AnalyticsOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest --tests com.miriyum.architecture.DomainPackageArchitectureTest --console=plain
```

Expected: PASS, including foreign Entity/Repository dependency enforcement.

- [x] **Step 5: Commit the consumer slice**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsService.java backend/src/test/java/com/miriyum/domain/analytics/service/StoreDashboardAnalyticsServiceTest.java backend/src/test/java/com/miriyum/domain/analytics/controller/StoreDashboardAnalyticsControllerTest.java backend/src/test/java/com/miriyum/domain/analytics/AnalyticsOpenApiContractTest.java docs/specs/analytics/openapi.yaml
git commit -m "feat(analytics): compose confirmed reservation no-shows"
```

### Task 11: Verify and publish the phase-2 slice

- [x] **Step 1: Re-run only affected actual-MySQL integration classes**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.service.ReservationAnalyticsQueryServiceIT --tests com.miriyum.domain.analytics.service.StoreDashboardAnalyticsServiceIT --tests com.miriyum.domain.analytics.controller.StoreDashboardAnalyticsHttpIT --console=plain
```

Expected: PASS. Do not run local `build`, `check`, full `integrationTest`, or all integration shards.

- [x] **Step 2: Lint and bundle the active contract**

```powershell
cd ..
npx --yes @redocly/cli@2.35.1 lint analytics docs/specs/store-operator-openapi.yaml
npx --yes @redocly/cli@2.35.1 bundle analytics --output NUL
```

Expected: both commands exit 0. On the known Windows Node shutdown assertion, retry once and report both attempts honestly.

- [x] **Step 3: Verify the exact scope**

```powershell
git diff --name-only origin/dev...HEAD
git diff --check
```

Expected: no new migration, frontend, Pro analytics, personal-row, or foreign domain internals in the phase-2 diff.

- [ ] **Step 4: Push and report CI status**

Push `feature/270-store-dashboard-analytics`, update PR #389 and Issue #270 with the confirmed contract activation, and leave full Backend CI as authoritative. Do not claim full verification until the exact pushed commit passes GitHub checks.
