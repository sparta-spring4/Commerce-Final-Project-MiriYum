# Reservation Cancellation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use `superpowers:subagent-driven-development` (recommended) or `superpowers:executing-plans` to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement Issue #51 so an authenticated consumer or store operator can cancel a scoped `CONFIRMED` reservation exactly once while atomically restoring capacity and an optional MenuHold, recording one success audit, and replaying the original `ReservationDetail` data.

**Architecture:** `ReservationCancellationCommandFacade` owns actor-specific command construction, the one-time `requestedAt`, fingerprints, correlation IDs, and bounded technical retries. `ReservationService` owns the `READ_COMMITTED` transaction, read-only account/ownership gates, the first contended idempotency claim, actor-scoped Reservation lock, stored-policy evaluation, MenuHold prelock, one ordered capacity lock, exact-once restoration, audit, detail projection, and idempotency result. Controllers remain thin actor-specific HTTP boundaries; Flyway V26 and a Reservation-owned audit entity preserve successful cancellation facts without backfilling legacy rows.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring MVC/Security/Data JPA, Bean Validation, Flyway, MySQL 8.0.40 Testcontainers, Gradle 9.6.1 Wrapper, JUnit 5, AssertJ, Mockito, MockMvc, SnakeYAML.

## Global Constraints

- Work only on Issue #51's exact 32-path allowlist. Do not modify, stage, or commit `.idea/**`, `.superpowers/**`, #168 policy files, or another domain's Entity/Repository.
- Run every command below from the repository root with Windows PowerShell syntax. Use `backend\gradlew.bat -p backend ...`; do not silently substitute an unregistered runner.
- Before Task 1, use `gh api "repos/sparta-spring4/Commerce-Final-Project-MiriYum/contents/backend/src/main/resources/db/migration?ref=dev" --jq '.[].name'`, list the local migration directory, and confirm V26 is still free on live `dev`. If it is occupied, stop, update Issue #51's migration path and exact allowlist first, then replace every V26 reference in this plan with the approved next number.
- Use existing `IdempotencyKey.parse(String)`, `IdempotencyCommand`, `IdempotencyExecutor`, `BusinessResult`, and `RequestFingerprint`; do not add a validator, fallback overload, second replay store, or envelope/message persistence.
- The business key is `(consumer|store-operator, actorAccountId, RESERVATION_CANCEL, normalizedKey)`. The exact audit/MenuHold correlation is `reservation-cancel:{consumer|store-operator}:{actorId}:{normalizedKey}`, excludes `reservationId`, and is at most 90 characters.
- The request fingerprint includes method, normalized route template, route IDs, and reason in a fixed order with explicit null encoding. A same-key/different-fingerprint request fails as `COMMON_007` before Reservation or resource mutation.
- Obtain `requestedAt` once in the facade before transaction/lock wait and reuse it for all retry attempts. Obtain `occurredAt` once after capacity/MenuHold restoration and use the same `Instant` for `Reservation.cancelledAt` and audit `occurredAt`.
- Before idempotency, perform only the consumer active-account gate or operator active-account/current management-ownership gate. The idempotency claim/replay must be the first contended lock.
- A fresh command locks Reservation once with actor scope: `(reservationId, consumerAccountId)` or `(reservationId, storeId)`. Consumer absence/foreign ownership and in-scope store absence are `RESERVATION_001`; do not probe by ID afterward.
- Reject non-`CONFIRMED` fresh commands with `RESERVATION_005`. For `CONFIRMED`, reject null `cancellationPolicyVersion` or null `startAt` before evaluator invocation with `RESERVATION_006`; pass non-null `(storedVersion, actor, status, startAt, requestedAt)` in that order and map `REJECTED_BY_POLICY` to `RESERVATION_006`.
- Use `MenuHoldService.lockForTermination(reservationId)` after Reservation lock and before capacity locks. Call `release(new MenuHoldReleaseCommand(reservationId, correlationId))` only for `HOLD_PRESENT`; do nothing for `NO_HOLD`. Preserve MenuHold/Store errors.
- Union and deduplicate original allocation bucket IDs with the current latest-policy materialized bucket IDs. Lock the complete union in one `WHERE id IN (...) ORDER BY id ASC` pessimistic query, verify all requested rows and policy invariants, then restore the full party size and exactly one team once per bucket. Never delete allocation history.
- Fresh success persists only HTTP status, response code, resource type/id, and `ReservationDetail` data. Replay returns the stored data without rereading Reservation, capacity, MenuHold, or audit. Future GET uses an optional audit query; a pre-V26 `CANCELLED` row has null actor/reason.
- Consumer reason is optional; when present it is 1-500 characters. Store-operator reason is required and 1-500 characters. Do not invent trimming or whitespace-only rejection beyond the OpenAPI/Bean Validation contract.
- Both cancellation endpoints return HTTP 200 in the common success envelope. Keep `cancelledBy` and `cancellationReason` in `ReservationDetail`; do not expose `cancelledAt`.
- Retry only MySQL deadlock 1213, lock timeout 1205 surfaced as `CannotAcquireLockException`, and `ObjectOptimisticLockingFailureException`, for at most three total attempts with 100-200 ms then 300-500 ms jitter outside the transaction. Map exhaustion/interruption to `COMMON_008`; never retry validation, domain, query timeout, or data-integrity failures.
- Each production change starts with the specified failing test and observed RED. Keep each commit limited to the paths named in that task. Never use broad `git add .` or `git add -A`.
- Existing successful #49 creation IT, prior full build, and full integration shards are not repeated unless a newly observed impact demands them. `ReservationServiceTest` is directly impacted and must be rerun fresh.

---

## File Responsibility Map

### Existing design and this plan

- `docs/superpowers/specs/2026-08-07-reservation-cancellation-design.md` — already-approved design input; no implementation task edits it.
- `docs/superpowers/plans/2026-08-07-reservation-cancellation.md` — this executable TDD plan; Task 8 verifies it remains in the exact diff.

### Task 1 — HTTP data contract

- `docs/specs/reservation/openapi.yaml` — both cancellation operations, request shapes, stable detail fields, and separate 409 examples.
- `backend/src/main/java/com/miriyum/domain/reservation/dto/request/ConsumerCancellationRequest.java` — optional 1-500 character consumer reason.
- `backend/src/main/java/com/miriyum/domain/reservation/dto/request/StoreCancellationRequest.java` — required 1-500 character operator reason.
- `backend/src/main/java/com/miriyum/domain/reservation/dto/response/ReservationDetailResponse.java` — nullable audit projection without public `cancelledAt`.
- `backend/src/test/java/com/miriyum/domain/reservation/dto/request/ConsumerCancellationRequestTest.java` — consumer DTO boundary tests.
- `backend/src/test/java/com/miriyum/domain/reservation/dto/request/StoreCancellationRequestTest.java` — operator DTO boundary tests.
- `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationDetailResponseTest.java` — fresh/legacy cancellation projection and exact record components.
- `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java` — executable OpenAPI contract checks.

### Task 2 — success audit persistence

- `backend/src/main/resources/db/migration/V26__create_reservation_cancellation_audits.sql` — append-only successful cancellation audit schema.
- `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCancellationAudit.java` — validated success audit snapshot.
- `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCancellationAuditRepository.java` — save and optional reservation lookup.
- `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCancellationAuditTest.java` — factory/invariant tests.
- `backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java` — clean migration, V25 upgrade, DB constraints, and JPA round trip.

### Task 3 — capacity restoration primitives

- `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucket.java` — guarded people/team restoration.
- `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java` — deterministic original allocation read.
- `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java` — latest-version observation and one PK-ascending union lock.
- `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucketTest.java` — exact decrement and underflow protection.

### Task 4 — transactional domain orchestration

- `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationCancellationPolicyConfig.java` — registers the existing evaluator using the existing registry.
- `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java` — two actor-scoped `FOR UPDATE` selectors.
- `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandResult.java` — HTTP status plus detail data result.
- `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java` — cancellation transaction, replay decoding, and future detail audit lookup.
- `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java` — lock order, policy, restoration, audit, replay, rollback-facing unit behavior, and query projection.

### Task 5 — command facade and retry boundary

- `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandFacade.java` — actor-specific public entries, fingerprints, correlation, one `requestedAt`, and bounded retry.
- `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandFacadeTest.java` — exact commands, 90-character boundary, time reuse, and retry classification.

### Task 6 — HTTP and security boundaries

- `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java` — consumer and operator reservation-family fail-closed chains.
- `backend/src/main/java/com/miriyum/domain/reservation/controller/ReservationController.java` — consumer cancellation POST.
- `backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java` — store-operator cancellation POST.
- `backend/src/test/java/com/miriyum/domain/reservation/controller/ReservationControllerTest.java` — consumer MockMvc/security contract.
- `backend/src/test/java/com/miriyum/domain/reservation/controller/StoreReservationControllerTest.java` — operator MockMvc/security contract.

### Task 7 — MySQL transaction and concurrency evidence

- `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationIT.java` — real MySQL atomicity, replay, concurrent cancellation, resource restoration, carry-over, and failure injection.

## Interfaces Produced Across Tasks

Task implementations must use these names and signatures consistently.

```java
public record ConsumerCancellationRequest(
        @Size(min = 1, max = 500) String reason
) {}

public record StoreCancellationRequest(
        @NotNull @Size(min = 1, max = 500) String reason
) {}

public record ReservationCancellationCommandResult(
        int httpStatus,
        ReservationDetailResponse data
) {}

public interface ReservationCancellationAuditRepository
        extends JpaRepository<ReservationCancellationAudit, Long> {
    Optional<ReservationCancellationAudit> findByReservationId(Long reservationId);
}

public interface ReservationCapacityAllocationRepository
        extends JpaRepository<ReservationCapacityAllocation, Long> {
    List<ReservationCapacityAllocation>
            findAllByReservationIdOrderByCapacityBucketIdAsc(Long reservationId);
}
```

`ReservationRepository` produces:

```java
Optional<Reservation> findByIdAndConsumerAccountIdForUpdate(
        Long reservationId, Long consumerAccountId);

Optional<Reservation> findByIdAndStoreIdForUpdate(
        Long reservationId, Long storeId);
```

Both are explicit `@Lock(PESSIMISTIC_WRITE)` JPQL queries ordered/scoped by the named IDs and must not add a fallback ID-only query.

`ReservationCapacityBucketRepository` produces:

```java
Optional<Long> findLatestPolicyVersion(long storeId, LocalDate serviceDate);

List<ReservationCapacityBucket> findAllByIdInForUpdate(Collection<Long> bucketIds);
```

The second method is an explicit pessimistic JPQL query with `where bucket.id in :bucketIds order by bucket.id asc`. Current candidates continue to use the existing `findLatestPolicyBucketsOverlapping(Collection<Long>, LocalDate, LocalTime, LocalTime)` read.

`ReservationCapacityBucket` produces:

```java
public void restore(int people, int teams)
```

It requires positive arguments, requires `teams == 1`, rejects `occupiedPeople < people` or `occupiedTeams < teams` with `IllegalStateException`, and mutates both counters only after all checks pass.

`ReservationCancellationAudit` produces:

```java
public static ReservationCancellationAudit recordSuccess(
        Long reservationId,
        ReservationCancellationActorType actorType,
        Long actorId,
        String cancellationReason,
        Instant requestedAt,
        Instant occurredAt,
        ReservationStatus beforeStatus,
        ReservationStatus afterStatus,
        long cancellationPolicyVersion,
        long capacityPolicyVersion,
        String commandId)
```

It also exposes getters for every persisted field. It accepts only `CONFIRMED -> CANCELLED`, positive IDs/versions, `occurredAt >= requestedAt`, nullable consumer reason or 1-500 characters, mandatory 1-500 character operator reason, and a nonblank command ID of at most 100 characters.

`ReservationDetailResponse` adds the final two components and overload:

```java
String cancelledBy,
String cancellationReason

public ReservationDetailResponse(
        String reservationId,
        String storeId,
        String storeName,
        LocalDate serviceDate,
        CustomerReservationTimeStatus timeStatus,
        OffsetDateTime startAt,
        OffsetDateTime serviceEndAt,
        String timeZoneId,
        ReservationPartyResponse party,
        String status,
        List<ReservationMenuSelectionResponse> menuSelections,
        OffsetDateTime createdAt) {
    this(reservationId, storeId, storeName, serviceDate, timeStatus,
            startAt, serviceEndAt, timeZoneId, party, status,
            menuSelections, createdAt, null, null);
}

public static ReservationDetailResponse from(
        Reservation reservation,
        List<MenuHoldItemResult> menuSnapshots,
        ReservationCancellationActorType cancelledBy,
        String cancellationReason)
```

The existing two-argument `from` remains and delegates with null audit fields. The exact 12-argument auxiliary constructor above also remains available so current production/tests outside Issue #51's allowlist stay source-compatible while the 14-component canonical record constructor serves cancellation-aware callers.

`ReservationService` produces:

```java
@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
public ReservationCancellationCommandResult cancelConsumerReservation(
        long consumerAccountId,
        long reservationId,
        IdempotencyCommand command,
        String reason,
        Instant requestedAt,
        String correlationId)

@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
public ReservationCancellationCommandResult cancelStoreReservation(
        long operatorAccountId,
        long storeId,
        long reservationId,
        IdempotencyCommand command,
        String reason,
        Instant requestedAt,
        String correlationId)
```

`ReservationCancellationCommandFacade` produces:

```java
public ReservationCancellationCommandResult cancelByConsumer(
        long consumerAccountId,
        long reservationId,
        IdempotencyKey key,
        ConsumerCancellationRequest request)

public ReservationCancellationCommandResult cancelByStoreOperator(
        long operatorAccountId,
        long storeId,
        long reservationId,
        IdempotencyKey key,
        StoreCancellationRequest request)
```

---

### Task 1: Lock the OpenAPI, request validation, and stable detail shape

**Files:**
- Modify: `docs/specs/reservation/openapi.yaml`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/dto/request/ConsumerCancellationRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/dto/request/StoreCancellationRequest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/dto/response/ReservationDetailResponse.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/dto/request/ConsumerCancellationRequestTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/dto/request/StoreCancellationRequestTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationDetailResponseTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java`

**Interfaces:**
- Consumes: existing `ReservationCancellationActorType`, `ReservationDetailResponse.from(Reservation, List<MenuHoldItemResult>)`, Validator, and reservation OpenAPI cancellation paths.
- Produces: the two request records and three-argument detail factory listed above; a stable JSON detail with nullable `cancelledBy`/`cancellationReason` and no `cancelledAt`.

- [ ] **Task 1 gate:** Complete one RED/GREEN cycle for DTO/detail tests and one for the OpenAPI test; review only the eight paths above.

- [ ] **Step 1: Write failing request DTO and detail projection tests.**

Create validator-based tests with concrete boundaries:

```java
@Test
void consumerReasonMayBeNullButMustBeOneToFiveHundredWhenPresent() {
    assertThat(violations(new ConsumerCancellationRequest(null))).isEmpty();
    assertThat(violations(new ConsumerCancellationRequest(" "))).isEmpty();
    assertThat(violations(new ConsumerCancellationRequest(""))).isNotEmpty();
    assertThat(violations(new ConsumerCancellationRequest("a".repeat(500)))).isEmpty();
    assertThat(violations(new ConsumerCancellationRequest("a".repeat(501)))).isNotEmpty();
}

@Test
void operatorReasonIsRequiredAndOneToFiveHundredCharacters() {
    assertThat(violations(new StoreCancellationRequest(null))).isNotEmpty();
    assertThat(violations(new StoreCancellationRequest(""))).isNotEmpty();
    assertThat(violations(new StoreCancellationRequest(" "))).isEmpty();
    assertThat(violations(new StoreCancellationRequest("a".repeat(500)))).isEmpty();
    assertThat(violations(new StoreCancellationRequest("a".repeat(501)))).isNotEmpty();
}
```

In `ReservationDetailResponseTest`, change the exact component assertion to end with `cancelledBy`, `cancellationReason`, assert no `cancelledAt`, and add:

```java
@Test
void projectsCancellationActorAndReasonWithoutPublishingCancelledAt() {
    ReservationDetailResponse response = ReservationDetailResponse.from(
            resolvedReservation(), List.of(),
            ReservationCancellationActorType.STORE_OPERATOR, "재료 소진");
    assertThat(response.cancelledBy()).isEqualTo("STORE_OPERATOR");
    assertThat(response.cancellationReason()).isEqualTo("재료 소진");
    assertThat(Arrays.stream(ReservationDetailResponse.class.getRecordComponents())
            .map(RecordComponent::getName))
            .doesNotContain("cancelledAt");
}
```

- [ ] **Step 2: Run the focused tests and observe RED.**

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequestTest" --tests "com.miriyum.domain.reservation.dto.request.StoreCancellationRequestTest" --tests "com.miriyum.domain.reservation.dto.response.ReservationDetailResponseTest"
```

Expected: compilation fails because the two request records and the detail overload/components do not exist.

- [ ] **Step 3: Implement the minimal request records and detail overload.**

Use the exact record signatures from “Interfaces Produced Across Tasks.” Keep the existing two-argument factory and delegate it as follows:

```java
public static ReservationDetailResponse from(
        Reservation reservation,
        List<MenuHoldItemResult> menuSnapshots
) {
    return from(reservation, menuSnapshots, null, null);
}
```

Add the exact 12-argument auxiliary constructor from “Interfaces Produced Across Tasks,” delegating its last two canonical components to null; this is required because existing callers outside the allowlist directly instantiate the current response. The three-argument factory maps `cancelledBy == null ? null : cancelledBy.name()` and preserves all existing time/menu fields. Do not add a timestamp or trim the reason.

- [ ] **Step 4: Run the focused DTO/detail tests and observe GREEN.**

Run the Step 2 command again. Expected: all selected tests pass.

- [ ] **Step 5: Write the failing OpenAPI contract test.**

Add a test that loads reservation OpenAPI and asserts:

```java
assertCancellationOperation(consumerOperation,
        "#/components/schemas/ConsumerCancellationRequest");
assertCancellationOperation(operatorOperation,
        "#/components/schemas/StoreCancellationRequest");
assertThat(reservationStateConflict.toString())
        .contains("RESERVATION_005", "RESERVATION_006");
assertThat(list(detail.get("required")))
        .contains("cancelledBy", "cancellationReason");
assertThat(map(detail.get("properties")))
        .containsKeys("cancelledBy", "cancellationReason")
        .doesNotContainKey("cancelledAt");
```

Define `assertCancellationOperation(Map<String,Object>, String)` in the same test class to assert bearer auth, `IdempotencyKey`, required JSON body, HTTP 200 detail response, and 400/401/403/404/409 responses for each endpoint.

- [ ] **Step 6: Run the OpenAPI test and observe RED.**

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.dto.response.ReservationOpenApiContractTest"
```

Expected: the 409 component has only one `RESERVATION_005` example and the detail required list omits the two cancellation fields.

- [ ] **Step 7: Make the minimum OpenAPI changes.**

Keep both existing paths and schemas. Change `ReservationStateConflict` from one example to named `invalidState` and `cancellationPolicyRejected` examples with exact codes `RESERVATION_005` and `RESERVATION_006`. Add nullable `cancelledBy` and `cancellationReason` to `ReservationDetail.required`; retain `additionalProperties: false`, the existing actor enum, maxLength 500, and no `cancelledAt`.

- [ ] **Step 8: Run all Task 1 tests and scope checks.**

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.dto.request.*CancellationRequestTest" --tests "com.miriyum.domain.reservation.dto.response.ReservationDetailResponseTest" --tests "com.miriyum.domain.reservation.dto.response.ReservationOpenApiContractTest"
git diff --check
git diff --name-only
```

Expected: selected tests pass; changed paths are exactly the eight Task 1 paths plus the two already committed design/plan paths visible in the branch range.

- [ ] **Step 9: Commit Task 1 with exact staging.**

```powershell
git add docs/specs/reservation/openapi.yaml backend/src/main/java/com/miriyum/domain/reservation/dto/request/ConsumerCancellationRequest.java backend/src/main/java/com/miriyum/domain/reservation/dto/request/StoreCancellationRequest.java backend/src/main/java/com/miriyum/domain/reservation/dto/response/ReservationDetailResponse.java backend/src/test/java/com/miriyum/domain/reservation/dto/request/ConsumerCancellationRequestTest.java backend/src/test/java/com/miriyum/domain/reservation/dto/request/StoreCancellationRequestTest.java backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationDetailResponseTest.java backend/src/test/java/com/miriyum/domain/reservation/dto/response/ReservationOpenApiContractTest.java
git commit -m "feat(reservation): define cancellation API contract"
```

### Task 2: Add the V26 successful cancellation audit

**Files:**
- Create: `backend/src/main/resources/db/migration/V26__create_reservation_cancellation_audits.sql`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCancellationAudit.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCancellationAuditRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCancellationAuditTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java`

**Interfaces:**
- Consumes: `reservations(reservation_id)`, `ReservationCancellationActorType`, `ReservationStatus`, V25 as the verified prior migration.
- Produces: `reservation_cancellation_audits`, the exact `recordSuccess(...)` factory, getters, and `findByReservationId(Long)`.

- [ ] **Task 2 gate:** Prove Java invariants first, then clean-schema and V25-upgrade behavior in MySQL; do not use H2 as migration evidence.

- [ ] **Step 1: Write the failing entity tests.**

Cover one valid consumer audit, one valid operator audit, and each invariant:

```java
@Test
void recordsExactSuccessfulOperatorCancellation() {
    ReservationCancellationAudit audit = ReservationCancellationAudit.recordSuccess(
            77L, STORE_OPERATOR, 33L, "재료 소진", REQUESTED_AT, OCCURRED_AT,
            CONFIRMED, CANCELLED, 1L, 7L,
            "reservation-cancel:store-operator:33:550e8400-e29b-41d4-a716-446655440000");
    assertThat(audit.getReservationId()).isEqualTo(77L);
    assertThat(audit.getActorType()).isEqualTo(STORE_OPERATOR);
    assertThat(audit.getCancellationReason()).isEqualTo("재료 소진");
    assertThat(audit.getRequestedAt()).isEqualTo(REQUESTED_AT);
    assertThat(audit.getOccurredAt()).isEqualTo(OCCURRED_AT);
}
```

Use `assertThatThrownBy` for nonpositive IDs/versions, missing operator reason, empty/501-character reason, reversed timestamps, transitions other than `CONFIRMED -> CANCELLED`, blank/101-character command. Add a boundary assertion accepting the exact 90-character Long.MAX_VALUE correlation.

- [ ] **Step 2: Run the entity test and observe RED.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.entity.ReservationCancellationAuditTest"
```

Expected: compilation fails because `ReservationCancellationAudit` is absent.

- [ ] **Step 3: Implement the audit entity and repository.**

Map the table/columns exactly:

```java
@Entity
@Table(name = "reservation_cancellation_audits",
        uniqueConstraints = @UniqueConstraint(
                name = "uk_reservation_cancellation_audits_reservation",
                columnNames = "reservation_id"))
public class ReservationCancellationAudit {
    @Id @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "reservation_cancellation_audit_id")
    private Long id;
    // reservationId, actorType, actorId, cancellationReason, requestedAt,
    // occurredAt, beforeStatus, afterStatus, cancellationPolicyVersion,
    // capacityPolicyVersion, commandId using the factory contract above.
}
```

Use a protected no-arg constructor, private validated constructor, `@Enumerated(EnumType.STRING)`, and no setter/builder. Create the repository signature exactly as listed.

- [ ] **Step 4: Run the entity test and observe GREEN.**

Run the Step 2 command again. Expected: all entity cases pass.

- [ ] **Step 5: Write failing MySQL migration tests before the SQL file.**

Update cleanup to delete audits before reservations. Add tests which:

- assert Flyway applied `V26__create_reservation_cancellation_audits.sql`;
- persist a Reservation then `recordSuccess(...)`, flush/clear, and read it through `findByReservationId`;
- use JDBC inserts to reject duplicate reservation audit, missing reservation FK, invalid actor, nonpositive actor/version, operator null reason, empty/501-character reason, occurred-before-requested, wrong statuses, and command over 100;
- accept nullable consumer reason and a 90-character correlation;
- start a separate `mysql:8.0.40` container, migrate to V25, insert a V25 `CANCELLED` reservation, migrate to latest, and assert the reservation bytes/fields are unchanged and no audit was invented.

The upgrade test must use:

```java
Flyway.configure().dataSource(...)
        .target(MigrationVersion.fromVersion("25")).load().migrate();
// insert legacy parents and CANCELLED reservation
Flyway upgraded = Flyway.configure().dataSource(...).load();
upgraded.migrate();
```

- [ ] **Step 6: Run the class-filtered migration IT and observe RED.**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest"
```

Expected: Flyway/JPA fails because V26 and the audit table are absent.

- [ ] **Step 7: Create V26 with all DB constraints.**

Use this schema shape, with explicit named constraints and utf8mb4:

```sql
CREATE TABLE reservation_cancellation_audits (
    reservation_cancellation_audit_id BIGINT NOT NULL AUTO_INCREMENT,
    reservation_id BIGINT NOT NULL,
    actor_type VARCHAR(20) NOT NULL,
    actor_id BIGINT NOT NULL,
    cancellation_reason VARCHAR(500) NULL,
    requested_at DATETIME(6) NOT NULL,
    occurred_at DATETIME(6) NOT NULL,
    before_status VARCHAR(20) NOT NULL,
    after_status VARCHAR(20) NOT NULL,
    cancellation_policy_version BIGINT NOT NULL,
    capacity_policy_version BIGINT NOT NULL,
    command_id VARCHAR(100) NOT NULL,
    PRIMARY KEY (reservation_cancellation_audit_id),
    CONSTRAINT uk_reservation_cancellation_audits_reservation UNIQUE (reservation_id),
    CONSTRAINT fk_reservation_cancellation_audits_reservation
        FOREIGN KEY (reservation_id) REFERENCES reservations (reservation_id)
        ON DELETE RESTRICT,
    CONSTRAINT ck_reservation_cancellation_audits_actor_type
        CHECK (actor_type IN ('CONSUMER', 'STORE_OPERATOR')),
    CONSTRAINT ck_reservation_cancellation_audits_actor_id CHECK (actor_id > 0),
    CONSTRAINT ck_reservation_cancellation_audits_reason CHECK (
        (cancellation_reason IS NULL OR CHAR_LENGTH(cancellation_reason) BETWEEN 1 AND 500)
        AND (actor_type <> 'STORE_OPERATOR' OR cancellation_reason IS NOT NULL)
    ),
    CONSTRAINT ck_reservation_cancellation_audits_time CHECK (occurred_at >= requested_at),
    CONSTRAINT ck_reservation_cancellation_audits_transition
        CHECK (before_status = 'CONFIRMED' AND after_status = 'CANCELLED'),
    CONSTRAINT ck_reservation_cancellation_audits_versions
        CHECK (cancellation_policy_version > 0 AND capacity_policy_version > 0)
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- [ ] **Step 8: Run entity and migration tests GREEN and check scope.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.entity.ReservationCancellationAuditTest"
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest"
git diff --check
git diff --name-only HEAD
```

Expected: both commands pass; only the five Task 2 paths are uncommitted.

- [ ] **Step 9: Commit Task 2 with exact staging.**

```powershell
git add backend/src/main/resources/db/migration/V26__create_reservation_cancellation_audits.sql backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCancellationAudit.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCancellationAuditRepository.java backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCancellationAuditTest.java backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java
git commit -m "feat(reservation): persist cancellation success audit"
```

### Task 3: Add exact-once capacity restoration primitives

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucket.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucketTest.java`

**Interfaces:**
- Consumes: immutable `ReservationCapacityAllocation` fields and existing latest overlapping-bucket read.
- Produces: `restore(int,int)`, deterministic original allocations, latest version observation, and one ordered union lock as specified above.

- [ ] **Task 3 gate:** The entity mutation must be independently safe before repository locks are consumed by Task 4.

- [ ] **Step 1: Write failing bucket restoration tests.**

```java
@Test
void restoresPeopleAndExactlyOneTeamTogether() {
    ReservationCapacityBucket bucket = bucketWithOccupancy(5, 2);
    bucket.restore(3, 1);
    assertThat(bucket.getOccupiedPeople()).isEqualTo(2);
    assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
}

@Test
void rejectsUnderflowWithoutPartialMutation() {
    ReservationCapacityBucket bucket = bucketWithOccupancy(2, 1);
    assertThatThrownBy(() -> bucket.restore(3, 1))
            .isInstanceOf(IllegalStateException.class);
    assertThat(bucket.getOccupiedPeople()).isEqualTo(2);
    assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
}
```

Also reject zero/negative people, zero/two teams, and team underflow while preserving both original counters.

- [ ] **Step 2: Run the bucket test and observe RED.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.entity.ReservationCapacityBucketTest"
```

Expected: compilation fails because `restore(int,int)` does not exist.

- [ ] **Step 3: Implement the guarded mutation.**

Validate all arguments and both underflow conditions before assigning:

```java
public void restore(int people, int teams) {
    int validatedPeople = requirePositive(people, "people");
    if (teams != 1) {
        throw new IllegalArgumentException("teams must be exactly one");
    }
    if (occupiedPeople < validatedPeople || occupiedTeams < teams) {
        throw new IllegalStateException("capacity occupancy cannot be restored below zero");
    }
    occupiedPeople -= validatedPeople;
    occupiedTeams -= teams;
}
```

- [ ] **Step 4: Run the bucket test and observe GREEN.**

Run the Step 2 command again. Expected: all selected tests pass.

- [ ] **Step 5: Add the exact repository contracts and compile them.**

Add the allocation derived query and these JPQL queries:

```java
@Query("""
        select max(bucket.policyVersion)
        from ReservationCapacityBucket bucket
        where bucket.storeId = :storeId and bucket.serviceDate = :serviceDate
        """)
Optional<Long> findLatestPolicyVersion(
        @Param("storeId") long storeId,
        @Param("serviceDate") LocalDate serviceDate);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
        select bucket from ReservationCapacityBucket bucket
        where bucket.id in :bucketIds
        order by bucket.id asc
        """)
List<ReservationCapacityBucket> findAllByIdInForUpdate(
        @Param("bucketIds") Collection<Long> bucketIds);
```

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.entity.ReservationCapacityBucketTest"
git diff --check
```

Expected: compilation and bucket tests pass.

- [ ] **Step 6: Commit Task 3 with exact staging.**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucket.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucketTest.java
git commit -m "feat(reservation): add capacity restoration locks"
```

### Task 4: Implement the transactional cancellation orchestration

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationCancellationPolicyConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandResult.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java`

**Interfaces:**
- Consumes: Task 1 response, Task 2 audit/repository, Task 3 capacity contracts, existing evaluator signature, `MenuHoldService.lockForTermination/release`, `IdempotencyExecutor.execute`.
- Produces: both transactional service entries and `ReservationCancellationCommandResult` signatures listed above; existing consumer/operator GET now optionally projects audit data.

- [ ] **Task 4 gate:** Review this task as one transaction boundary. Its tests must verify call order and absence of side effects on every rejection/replay branch.

- [ ] **Step 1: Extend the service fixture and write RED tests for actor gates, replay, and policy.**

Add `@Mock ReservationCancellationAuditRepository`, `@Mock ReservationCancellationPolicyEvaluator`, and pass both through the production constructor. Capture the idempotency supplier so tests distinguish replay from fresh. Add concrete tests:

```java
@Test
void consumerActiveGateRunsBeforeIdempotencyAndForeignReservationIsHidden() {
    given(idempotencyExecutor.execute(eq(CONSUMER_COMMAND), any()))
            .willAnswer(runBusinessSupplier());
    given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
            .willReturn(Optional.empty());
    assertError(() -> cancelConsumer(), ReservationErrorCode.RESERVATION_NOT_FOUND);
    InOrder order = inOrder(consumerAccountService, idempotencyExecutor, reservationRepository);
    order.verify(consumerAccountService).requireActiveAccount(11L);
    order.verify(idempotencyExecutor).execute(eq(CONSUMER_COMMAND), any());
    order.verify(reservationRepository)
            .findByIdAndConsumerAccountIdForUpdate(77L, 11L);
}

@Test
void replayUsesStoredDetailWithoutReadingDomainState() {
    given(idempotencyExecutor.execute(eq(CONSUMER_COMMAND), any()))
            .willReturn(storedCancellationOutcome());
    ReservationCancellationCommandResult result = cancelConsumer();
    assertThat(result.httpStatus()).isEqualTo(200);
    assertThat(result.data().cancelledBy()).isEqualTo("CONSUMER");
    then(reservationRepository).shouldHaveNoInteractions();
    then(capacityBucketRepository).shouldHaveNoInteractions();
    then(menuHoldService).shouldHaveNoInteractions();
    then(cancellationAuditRepository).shouldHaveNoInteractions();
}
```

Add separate tests for operator `requireManagementOwnership` before idempotency, operator scoped lock, non-`CONFIRMED` -> RES005 with evaluator uncalled, null version/null start -> RES006 with evaluator uncalled, actual five-argument evaluator order, unknown version -> RES006, and V1 startAt before/equal/after requestedAt all allowed.

- [ ] **Step 2: Run selected service tests and observe RED.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest"
```

Expected: compilation fails because cancellation dependencies, repository locks, result, and service methods are absent.

- [ ] **Step 3: Register the evaluator and implement scoped Reservation locks.**

Add the existing-registry evaluator bean:

```java
@Bean
public ReservationCancellationPolicyEvaluator reservationCancellationPolicyEvaluator(
        ReservationCancellationPolicyRegistry registry) {
    return new ReservationCancellationPolicyEvaluator(registry);
}
```

Add two explicit repository JPQL methods with `@Lock(PESSIMISTIC_WRITE)`. The consumer query must contain both IDs; the operator query must contain reservation/store IDs. Neither query filters status, because the service must distinguish RES005 from 404 after actor scoping.

- [ ] **Step 4: Implement gate/idempotency/policy branches with no resource mutation yet, then rerun the targeted tests.**

Create the result record. In each service entry validate the request object values, run the actor read-only gate, then call:

```java
IdempotentOutcome outcome = idempotencyExecutor.execute(command, () ->
        cancelReservationWork(actorType, actorId, scopedStoreId, reservationId,
                reason, requestedAt, correlationId));
return cancellationResult(outcome);
```

In `cancelReservationWork`, lock actor scope, check status, check null version/start, and evaluate in exact order. Use `ServiceException(INVALID_STATE_TRANSITION)` and `ServiceException(CANCELLATION_NOT_ALLOWED)` exactly. At this intermediate point, immediately after ALLOWED, continue implementing Steps 5-7 before claiming GREEN; do not insert a fake return.

- [ ] **Step 5: Write RED tests for MenuHold prelock, original/current union, one lock, and exact restoration.**

Build two-bucket fixtures with IDs. The key assertions are:

```java
InOrder order = inOrder(reservationRepository, menuHoldService,
        capacityAllocationRepository, capacityBucketRepository,
        cancellationAuditRepository);
order.verify(reservationRepository).findByIdAndConsumerAccountIdForUpdate(77L, 11L);
order.verify(menuHoldService).lockForTermination(77L);
order.verify(capacityAllocationRepository)
        .findAllByReservationIdOrderByCapacityBucketIdAsc(77L);
order.verify(capacityBucketRepository).findAllByIdInForUpdate(List.of(301L, 302L, 401L));
```

Cover all of these cases with concrete assertions:

- original allocations store full party and one team in every row; do not sum allocation people;
- current version equals original: current IDs must equal original IDs and dedupe prevents double restore;
- newer latest version: overlapping current bucket IDs are unioned with originals, sorted/deduped, and each gets one `restore(totalParty, 1)`;
- missing requested bucket, mismatched original people/team/version, latest-version change before/after union, and underflow all throw before Reservation/audit success;
- `NO_HOLD` never invokes `release`; `HOLD_PRESENT` invokes exactly one release after capacity restore with the same correlation and validates `reservationId` plus `Outcome.RELEASED`.

- [ ] **Step 6: Implement the resource algorithm minimally.**

After policy ALLOWED:

1. Call `lockForTermination` and require a non-null enum.
2. Read original allocations ordered by bucket ID; require at least one and verify every row is for the reservation's full party, exactly one team, and the stored capacity policy.
3. Read latest version and existing latest overlapping buckets for `List.of(storeId)`, service date, local start, and local occupancy end. Reject absent/inconsistent versions as `CAPACITY_POLICY_CHANGED`.
4. If latest version equals the reservation version, require current candidate IDs equal original IDs. If newer, treat the current overlapping candidates as the publication-materialized set. Intermediate retired versions are not added.
5. Build a `TreeSet<Long>` union, call `findAllByIdInForUpdate` once, and compare returned IDs exactly with the requested sorted IDs.
6. Re-read latest version and require it unchanged. Restore `reservation.getParty().totalCount()` and one team once per locked union bucket.
7. For `HOLD_PRESENT`, call `release(new MenuHoldReleaseCommand(reservationId, correlationId))` and validate the exact result; for `NO_HOLD`, skip it.

Use `CAPACITY_POLICY_CHANGED` for current-policy races and `IllegalStateException` for corrupted stored allocation/bucket invariants; both roll back and neither is guessed as success.

- [ ] **Step 7: Write RED tests for occurredAt, audit, data-only result, and future GET.**

Use a mock/fixed Clock sequence so the service's `occurredAt` is known. Capture the saved audit and assert:

```java
assertThat(reservation.getStatus()).isEqualTo(CANCELLED);
assertThat(reservation.getCancelledAt()).isEqualTo(OCCURRED_AT);
assertThat(audit.getOccurredAt()).isEqualTo(OCCURRED_AT);
assertThat(audit.getRequestedAt()).isEqualTo(REQUESTED_AT);
assertThat(audit.getCommandId()).isEqualTo(CORRELATION_ID);
assertThat(result.data().cancelledBy()).isEqualTo("CONSUMER");
```

Also assert `BusinessResult` uses `200`, `SUCCESS`, resource type `RESERVATION`, resource ID `77`, and only `ReservationDetailResponse` data. Add GET tests where `findByReservationId` returns an audit (actor/reason present) or empty (both null, including legacy CANCELLED). Add failure tests proving audit save exception prevents idempotency success and that replay never saves another audit.

- [ ] **Step 8: Complete the success/audit/detail implementation and replay decoding.**

After resources succeed, call `clock.instant()` once, capture `beforeStatus`, call `reservation.cancel(occurredAt)`, create/save the audit, obtain menu snapshots, and build:

```java
ReservationDetailResponse response = ReservationDetailResponse.from(
        reservation, menuSnapshots, actorType, reason);
return new BusinessResult<>(200, "SUCCESS", "RESERVATION",
        String.valueOf(reservation.getId()), response);
```

`cancellationResult(IdempotentOutcome)` deserializes stored data with the existing `ObjectMapper` and reconstructs nullable OffsetDateTime fields the same way creation replay does, including the new actor/reason fields. Update both GET detail methods to query `cancellationAuditRepository.findByReservationId(reservation.getId())` and pass optional actor/reason; do not query audit for replay.

- [ ] **Step 9: Run the full impacted service class GREEN and inspect scope.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest"
git diff --check
git diff --name-only HEAD
```

Expected: the entire impacted service test class passes, preserving creation/query/time-policy tests; only the five Task 4 paths are uncommitted.

- [ ] **Step 10: Commit Task 4 with exact staging.**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/config/ReservationCancellationPolicyConfig.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandResult.java backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java
git commit -m "feat(reservation): orchestrate atomic cancellation"
```

### Task 5: Add actor-specific facade commands and bounded retries

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandFacade.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandFacadeTest.java`

**Interfaces:**
- Consumes: Task 1 request DTOs, Task 4 service entries/result, existing `IdempotencyKey.value()` and `RequestFingerprint.of`.
- Produces: actor-specific facade methods listed above; no public generic actor method.

- [ ] **Task 5 gate:** Fingerprint/correlation/time semantics and retry classification must be proven without a Spring context.

- [ ] **Step 1: Write failing command construction and requestedAt tests.**

Capture service arguments for both entries. Assert exact command fields and route-sensitive fingerprints by comparing against `RequestFingerprint.of` of length-prefixed canonical strings. Use null encoding `field=-1:|`; non-null values use `field={length}:{value}|`.

```java
@Test
void consumerBuildsScopedCommandAndCorrelation() {
    facade.cancelByConsumer(11L, 77L, KEY, new ConsumerCancellationRequest(null));
    then(service).should().cancelConsumerReservation(
            eq(11L), eq(77L), commandCaptor.capture(), isNull(),
            eq(REQUESTED_AT),
            eq("reservation-cancel:consumer:11:" + KEY.value()));
    assertThat(commandCaptor.getValue().principalNamespace()).isEqualTo("consumer");
    assertThat(commandCaptor.getValue().commandType()).isEqualTo("RESERVATION_CANCEL");
}
```

For operator include both `storeId` and `reservationId` in the canonical input. Prove a key reused for a different route ID or reason produces a different fingerprint. Prove the Long.MAX_VALUE operator correlation equals exactly 90 characters and is passed unchanged.

- [ ] **Step 2: Run the facade test and observe RED.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest"
```

Expected: compilation fails because the facade does not exist.

- [ ] **Step 3: Implement public entries and exact canonicalization.**

Use `@Service`, an `@Autowired` constructor `(ReservationService, Clock)`, and a package-private test constructor `(ReservationService, Clock, IntToLongFunction, RetrySleeper)`. Each public entry validates non-null key/request, obtains `Instant requestedAt = clock.instant()` exactly once, constructs `IdempotencyCommand`, reason, correlation, then passes a supplier to one private retry method. Do not put `reservationId` in correlation.

Canonical prefixes are exact:

```text
POST|/api/v1/reservations/{reservationId}/cancellations|
POST|/api/v1/store-operator/stores/{storeId}/reservations/{reservationId}/cancellations|
```

Append consumer `reservationId`, `reason`; append operator `storeId`, `reservationId`, `reason`.

- [ ] **Step 4: Write retry classification tests before implementing retry.**

Concrete cases:

- deadlock 1213 then lock timeout 1205 then success -> service called 3 times, delays exactly supplied 100/300, same captured `requestedAt` each call;
- third retryable failure -> `COMMON_008` with original cause;
- `ObjectOptimisticLockingFailureException` retries;
- `ServiceException`, `IllegalArgumentException`, `QueryTimeoutException`, `DataIntegrityViolationException`, and unrelated `CannotAcquireLockException` do not retry;
- interruption restores thread interrupt flag and returns `COMMON_008`.

- [ ] **Step 5: Implement the bounded retry and run GREEN.**

Copy the proven classification shape from `ReservationCreationCommandFacade`, adding optimistic conflicts explicitly. Keep delays `ThreadLocalRandom.current().nextLong(100L, 201L)` and `nextLong(300L, 501L)`, and sleep outside service transactions.

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest"
git diff --check
```

Expected: all facade tests pass.

- [ ] **Step 6: Commit Task 5 with exact staging.**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandFacade.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandFacadeTest.java
git commit -m "feat(reservation): add cancellation command facade"
```

### Task 6: Expose the two cancellation endpoints and fail closed

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/ReservationController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/ReservationControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/controller/StoreReservationControllerTest.java`

**Interfaces:**
- Consumes: Task 1 DTOs/detail and Task 5 facade.
- Produces: the two approved POST routes with existing consumer/operator JWT namespaces and common envelopes.

- [ ] **Task 6 gate:** Replace only the old “cancellation denied” expectations. Existing create/detail/list security behavior must remain covered.

- [ ] **Step 1: Write failing consumer MockMvc tests.**

Replace `deniesUnimplementedCancellationPath` with tests that assert:

```java
given(cancellationFacade.cancelByConsumer(
        eq(11L), eq(77L), any(IdempotencyKey.class),
        any(ConsumerCancellationRequest.class)))
        .willReturn(new ReservationCancellationCommandResult(200, cancelledDetail()));

mockMvc.perform(post("/api/v1/reservations/77/cancellations")
        .header(AUTHORIZATION, "Bearer consumer-token")
        .header("Idempotency-Key", IDEMPOTENCY_KEY)
        .contentType(APPLICATION_JSON)
        .content("{\"reason\":\"일정 변경\"}"))
    .andExpect(status().isOk())
    .andExpect(jsonPath("$.code").value("SUCCESS"))
    .andExpect(jsonPath("$.data.status").value("CANCELLED"))
    .andExpect(jsonPath("$.data.cancelledBy").value("CONSUMER"))
    .andExpect(jsonPath("$.data.cancelledAt").doesNotExist());
```

Also test missing key -> COMMON_003/facade uncalled, malformed key -> COMMON_004, overlong reason/unknown field -> COMMON_001, omitted reason accepted, unauthenticated -> AUTH_001, operator token -> AUTH_004, and a representative `RESERVATION_006` passthrough. Keep create POST, detail GET, and unknown subpath deny tests.

- [ ] **Step 2: Write failing operator MockMvc tests.**

Import the Reservation-owned operator reservation security chain alongside the existing Store management chain. Test valid call propagation `(operatorId=33, storeId=22, reservationId=77, key, reason)`, HTTP 200/detail, missing/malformed key, missing/empty/501 reason, whitespace reason accepted, unknown field, unauthenticated AUTH_001, consumer token AUTH_004, Store `STORE_003` passthrough, in-store `RESERVATION_001`, and `RESERVATION_005/006` passthrough. Add an authenticated POST to `/reservations/77/unknown` and a nonapproved method to the cancellation path expecting AUTH_006.

- [ ] **Step 3: Run both controller tests and observe RED.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.controller.ReservationControllerTest" --tests "com.miriyum.domain.reservation.controller.StoreReservationControllerTest"
```

Expected: cancellation routes are unmapped/forbidden and controller facade beans/methods are missing.

- [ ] **Step 4: Implement thin controller methods.**

Consumer:

```java
@PostMapping("/{reservationId}/cancellations")
public ResponseEntity<ApiResponse<ReservationDetailResponse>> cancelReservation(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable long reservationId,
        @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
        @Valid @RequestBody ConsumerCancellationRequest request) {
    ReservationCancellationCommandResult result =
            reservationCancellationCommandFacade.cancelByConsumer(
                    principal.accountId(), reservationId,
                    IdempotencyKey.parse(rawKey), request);
    return ResponseEntity.status(result.httpStatus())
            .body(ApiResponse.success("예약이 취소되었습니다.", result.data()));
}
```

Operator uses the exact store path controller mapping, `@PathVariable @Positive long storeId`, unannotated reservation ID for hidden-not-found semantics, `StoreCancellationRequest`, and `cancelByStoreOperator`. Do not catch/remap `ServiceException`.

- [ ] **Step 5: Add the fail-closed security chains.**

Keep consumer create POST and detail GET; add only consumer cancellation POST. In the same config add an earlier (`@Order(-1)`) operator reservation-family chain so it wins before `StoreManagementSecurityConfig` for:

```text
/api/v1/store-operator/stores/*/reservations
/api/v1/store-operator/stores/*/reservations/**
```

Use `TokenNamespace.STORE_OPERATOR`, allow existing collection/detail GET plus exact cancellation POST, and `denyAll` every other method/subpath. The existing consumer chain remains `TokenNamespace.CONSUMER` and deny-all outside root create, detail GET, and exact cancellation POST.

- [ ] **Step 6: Run controller/security tests GREEN and scope checks.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.controller.ReservationControllerTest" --tests "com.miriyum.domain.reservation.controller.StoreReservationControllerTest"
git diff --check
git diff --name-only HEAD
```

Expected: both classes pass; only the five Task 6 paths are uncommitted.

- [ ] **Step 7: Commit Task 6 with exact staging.**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java backend/src/main/java/com/miriyum/domain/reservation/controller/ReservationController.java backend/src/main/java/com/miriyum/domain/reservation/controller/StoreReservationController.java backend/src/test/java/com/miriyum/domain/reservation/controller/ReservationControllerTest.java backend/src/test/java/com/miriyum/domain/reservation/controller/StoreReservationControllerTest.java
git commit -m "feat(reservation): expose cancellation endpoints"
```

### Task 7: Prove MySQL atomicity, replay, restoration, and concurrency

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationIT.java`

**Interfaces:**
- Consumes: production facade/service/repositories, real Global idempotency, Store/consumer account gates, real MenuHold release, V26.
- Produces: class-filtered MySQL evidence tagged `integration` and exactly one shard tag `integration-shard-b`.

- [ ] **Task 7 gate:** Use a real `mysql:8.0.40` container, latches/barriers instead of sleeps, bounded executor shutdown, and database snapshots before/after each injected failure.

- [ ] **Step 1: Create the tagged IT fixture and first failing no-menu cancellation test.**

Use the same Spring/Testcontainers properties and deterministic fixture patterns as `ReservationCreationIT`, but create a confirmed Reservation, original allocation, and occupied capacity directly in transactions. Cleanup order begins with cancellation audit, then MenuHold/inventory, allocation, Reservation, capacity, idempotency, Store/accounts.

```java
@Test
void noMenuCancellationCommitsStateCapacityAuditAndReplayOnce() {
    Scenario s = confirmedScenarioWithoutMenu();
    ReservationCancellationCommandResult first = facade.cancelByConsumer(
            s.consumerId(), s.reservationId(), key(1),
            new ConsumerCancellationRequest(null));
    ResourceSnapshot afterFirst = snapshot(s);
    ReservationCancellationCommandResult replay = facade.cancelByConsumer(
            s.consumerId(), s.reservationId(), key(1),
            new ConsumerCancellationRequest(null));
    assertThat(first.httpStatus()).isEqualTo(200);
    assertThat(replay.data()).isEqualTo(first.data());
    assertThat(snapshot(s)).isEqualTo(afterFirst);
    assertThat(afterFirst.status()).isEqualTo("CANCELLED");
    assertThat(afterFirst.auditCount()).isEqualTo(1);
    assertThat(afterFirst.occupiedPeople()).isZero();
    assertThat(afterFirst.occupiedTeams()).isZero();
}
```

- [ ] **Step 2: Run only the new IT and observe RED.**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT"
```

Expected: the new test fails until its full fixture and production wiring are correct; a context/migration failure is actionable RED, not a reason to run the full shard.

- [ ] **Step 3: Add concrete resource and policy scenarios.**

Add tests with explicit DB assertions for:

- `HOLD_PRESENT`: hold becomes RELEASED, original menu inventory is restored exactly once, release ledger uses the same facade correlation as audit command ID;
- `NO_HOLD`: no MenuHold rows/ledger are created or changed;
- two original buckets: each occupied people decreases by full party size and team by one exactly once; allocations remain unchanged;
- republished newer capacity: seed old original allocation plus newer overlapping buckets with materialized occupancy, then assert union restores old and current IDs, not an intermediate retired version;
- legacy `CONFIRMED` null cancellation version and null startAt each return RES006 and leave every snapshot unchanged;
- different key after successful cancellation returns RES005; same key/different reason returns COMMON_007;
- consumer foreign reservation returns RES001 and operator ownership failure preserves Store's public 403/404.

- [ ] **Step 4: Add deterministic concurrent cancellation tests.**

Use a fixed-size executor, `CountDownLatch ready/start`, and two independent calls:

```java
@Test
void sameKeyConcurrentCallsProduceOneEffectAndTwoEqualResponses() throws Exception { /* ... */ }

@Test
void differentKeysConcurrentCallsProduceOneSuccessAndOneReservation005() throws Exception { /* ... */ }
```

After each test assert one audit, one capacity restoration, one menu restoration, Reservation CANCELLED, and expected idempotency command rows. Put executor shutdown in `finally`, call `shutdownNow()`, and assert `awaitTermination(5, SECONDS)`.

- [ ] **Step 5: Add transaction failure injection tests using temporary MySQL triggers.**

Create/drop uniquely named triggers inside `try/finally`; never leave a trigger for the next test. Test these exact rollback points:

- capacity: seed occupancy below the party so guarded restore fails;
- MenuHold: `BEFORE UPDATE ON menu_holds` signals when `NEW.status='RELEASED'`;
- audit: `BEFORE INSERT ON reservation_cancellation_audits` signals;
- idempotency finalize: `BEFORE UPDATE ON idempotency_commands` signals when `NEW.status='SUCCEEDED' AND NEW.command_type='RESERVATION_CANCEL'`.

For every case capture Reservation status/timestamps, all affected capacity counters, MenuHold/inventory/ledger, audit count, and idempotency success fields before the command. Assert the post-failure snapshot equals the pre-command snapshot and there is no successful audit/result. Do not interpret a unique/audit conflict as replay success.

- [ ] **Step 6: Run the class-filtered IT fresh and inspect process cleanup.**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT" --rerun-tasks
backend\gradlew.bat -p backend --stop
Get-Process java -ErrorAction SilentlyContinue
```

Expected: the IT class passes; Gradle daemons are stopped. Report any remaining Java process without killing unrelated user processes. Testcontainers/Ryuk must have removed the test container.

- [ ] **Step 7: Commit Task 7 with exact staging.**

```powershell
git add backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationIT.java
git commit -m "test(reservation): verify cancellation transactions"
```

### Task 8: Fresh verification, exact-scope audit, and independent review

**Files:**
- Verify: all Issue #51 exact 32 allowlist paths, including the approved design and this plan.
- Modify: none unless an Important review finding is fixed in its owning task paths with a new focused RED/GREEN cycle and a separate commit.

**Interfaces:**
- Consumes: all Tasks 1-7 commits and Issue #51 current body fetched with `gh issue view 51`.
- Produces: fresh local evidence and an independent reviewer verdict; no push, PR, ready, or merge action without separate authorization.

- [ ] **Task 8 gate:** Completion claims require current HEAD command output, exact path equality, a clean tracked/staged tree, and an independent review with no unresolved Important finding.

- [ ] **Step 1: Run fresh focused unit/slice verification.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.dto.request.ConsumerCancellationRequestTest" --tests "com.miriyum.domain.reservation.dto.request.StoreCancellationRequestTest" --tests "com.miriyum.domain.reservation.dto.response.ReservationDetailResponseTest" --tests "com.miriyum.domain.reservation.dto.response.ReservationOpenApiContractTest" --tests "com.miriyum.domain.reservation.entity.ReservationCancellationAuditTest" --tests "com.miriyum.domain.reservation.entity.ReservationCapacityBucketTest" --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest" --tests "com.miriyum.domain.reservation.controller.ReservationControllerTest" --tests "com.miriyum.domain.reservation.controller.StoreReservationControllerTest" --rerun-tasks
```

Expected: all selected tests pass. This intentionally reruns the impacted `ReservationServiceTest`; it does not rerun unrelated #49 creation IT or a full build.

- [ ] **Step 2: Run fresh class-filtered MySQL verification only.**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest" --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT" --rerun-tasks
```

Expected: both MySQL classes pass. Do not replace this with `integrationTestShardB`, which would run unrelated classes.

- [ ] **Step 3: Stop Gradle workers and record any remaining process.**

```powershell
backend\gradlew.bat -p backend --stop
Get-Process java -ErrorAction SilentlyContinue
```

Expected: Gradle reports stopped daemons. Do not terminate Java processes that are not proven to belong to these tests.

- [ ] **Step 4: Verify formatting, migration slot, and exact 32-path equality.**

```powershell
gh api "repos/sparta-spring4/Commerce-Final-Project-MiriYum/contents/backend/src/main/resources/db/migration?ref=dev" --jq '.[].name'
Get-ChildItem backend/src/main/resources/db/migration | Sort-Object Name | Select-Object -ExpandProperty Name
gh issue view 51 --repo sparta-spring4/Commerce-Final-Project-MiriYum --json state,body,url
git diff --check origin/dev...HEAD
git diff --name-only origin/dev...HEAD | Sort-Object
git status --short --branch
git diff --cached --name-only
```

Expected: migration number still matches the Issue; the branch range equals all and only the exact 32 paths; staged is empty; tracked files are clean. `.idea/**` and `.superpowers/**` may remain untracked and untouched.

- [ ] **Step 5: Perform the plan self-review required by `writing-plans`.**

Run:

```powershell
rg -n "T[B]D|T[O]DO|implement l[a]ter|fill in d[e]tails|Similar to T[a]sk|add appr[o]priate|Write tests for the ab[o]ve" docs/superpowers/plans/2026-08-07-reservation-cancellation.md
rg -n "cancelConsumerReservation|cancelStoreReservation|cancelByConsumer|cancelByStoreOperator|recordSuccess|findAllByIdInForUpdate|findLatestPolicyVersion" docs/superpowers/plans/2026-08-07-reservation-cancellation.md
```

Expected: placeholder scan returns no matches; interface scan shows consistent definitions/usages. Manually map every design TDD bullet to Tasks 1-7 and every Issue allowlist path to the File Responsibility Map.

- [ ] **Step 6: Dispatch an independent code reviewer.**

Give a fresh reviewer Issue #51, the approved design, this plan, and `origin/dev...HEAD`. Require findings only for correctness, authorization, transaction/lock order, idempotency/replay, data integrity, security, migration compatibility, and missing tests. The reviewer must verify no cross-domain Entity/Repository access and no allowlist escape.

Expected: no Important finding. For an Important finding, return to its owning task, write a reproducing RED test, apply the minimum fix, rerun only impacted focused tests plus the relevant MySQL class, commit without amend, and request re-review.

- [ ] **Step 7: Record the final local handoff without publishing.**

Report exact commit range, the fresh commands and exit codes, selected test counts, 32/32 path equality, migration version, reviewer verdict, tracked/staged status, and deliberately skipped full build/shards with the non-duplication reason. Do not push or create/update a PR unless the user separately authorizes publication.
