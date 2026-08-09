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
- Do not separately repeat the successful #49 creation IT or standalone integration shards. `ReservationServiceTest` is directly impacted and must be rerun fresh, and Task 8 must run a fresh full `build --rerun-tasks` at current HEAD so the complete unit/slice and `integrationTest` surfaces are final evidence.

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
- `backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java` — Task 2 clean migration/V25 upgrade/DB constraints/JPA round trip, then Tasks 3-4 real-MySQL repository ordering, scope, and lock behavior.

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
git diff --name-only HEAD
git diff --name-only origin/dev...HEAD | Sort-Object
```

Expected: selected tests pass; `git diff --name-only HEAD` shows exactly the eight uncommitted Task 1 paths. At this pre-commit point the branch-range command shows only the two already committed design/plan paths; after Step 9 it will also show the eight Task 1 paths. Do not conflate working-tree scope with branch scope.

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

@Test
void acceptsWhitespaceAsAnOperatorReasonWithoutInventingTrimValidation() {
    ReservationCancellationAudit audit = ReservationCancellationAudit.recordSuccess(
            77L, STORE_OPERATOR, 33L, " ", REQUESTED_AT, OCCURRED_AT,
            CONFIRMED, CANCELLED, 1L, 7L,
            "reservation-cancel:store-operator:33:550e8400-e29b-41d4-a716-446655440000");
    assertThat(audit.getCancellationReason()).isEqualTo(" ");
}
```

Use `assertThatThrownBy` for nonpositive IDs/versions, missing operator reason, empty/501-character reason, reversed timestamps, transitions other than `CONFIRMED -> CANCELLED`, blank/101-character command. Add a boundary assertion accepting the exact 90-character Long.MAX_VALUE correlation.

- [ ] **Step 2: Run the entity test and observe RED.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.entity.ReservationCancellationAuditTest"
```

Expected: compilation fails because `ReservationCancellationAudit` is absent.

- [ ] **Step 3: Implement the audit entity and repository.**

Map `ReservationCancellationAudit` to `reservation_cancellation_audits` with the named unique constraint `uk_reservation_cancellation_audits_reservation`. Map the generated `Long id` to `reservation_cancellation_audit_id`; map `reservationId` as non-null; map actor and before/after status with `@Enumerated(EnumType.STRING)` and length 20; map `actorId` non-null; map reason nullable with length 500; map both `Instant` values and both positive policy-version longs non-null; map command ID non-null with length 100. Use a protected no-arg constructor, a private constructor called only by the exact `recordSuccess(...)` factory, getters for every persisted field, and no setter/builder. Create the repository signature exactly as listed.

- [ ] **Step 4: Run the entity test and observe GREEN.**

Run the Step 2 command again. Expected: all entity cases pass.

- [ ] **Step 5: Write failing MySQL migration tests before the SQL file.**

Update cleanup to delete audits before reservations. Add tests which:

- assert Flyway applied `V26__create_reservation_cancellation_audits.sql`;
- persist a Reservation then `recordSuccess(...)`, flush/clear, and read it through `findByReservationId`;
- use JDBC inserts to reject duplicate reservation audit, missing reservation FK, invalid actor, nonpositive actor/version, operator null reason, empty/501-character reason, occurred-before-requested, wrong statuses, and command over 100;
- accept nullable consumer reason, a STORE_OPERATOR reason equal to the single whitespace character `" "`, and a 90-character correlation in both JDBC and JPA round trips;
- start a separate `mysql:8.0.40` container, migrate to V25, insert a V25 `CANCELLED` reservation, migrate to latest, and assert the reservation bytes/fields are unchanged and no audit was invented.

The upgrade test must use:

```java
Flyway.configure().dataSource(
        legacyMysql.getJdbcUrl(),
        legacyMysql.getUsername(),
        legacyMysql.getPassword())
        .target(MigrationVersion.fromVersion("25")).load().migrate();
insertV25ParentsAndCancelledReservation(legacyMysql);
Flyway upgraded = Flyway.configure().dataSource(
        legacyMysql.getJdbcUrl(),
        legacyMysql.getUsername(),
        legacyMysql.getPassword()).load();
upgraded.migrate();
```

Define `insertV25ParentsAndCancelledReservation(MySQLContainer legacyMysql)` in `ReservationMigrationTest` using the existing `legacyConnection(...)` JDBC helper and fixed positive consumer/store/reservation IDs; it inserts every non-null V25 reservation column, sets status `CANCELLED` with a valid `cancelled_at`, and does not insert an audit row.

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
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java`

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

- [ ] **Step 5: Write executable MySQL repository contract tests and observe behavioral RED.**

In `ReservationMigrationTest`, add `allocationRepositoryContractReturnsBucketAscending` and `capacityRepositoryContractsReturnSortedRowsAndHoldWriteLocks`. Keep the tests source-compatible before the new methods exist by locating the exact method names on the Spring Data proxy with `Arrays.stream(repository.getClass().getMethods())`; assert with a description that the method is present before invoking it. The tests then:

1. persist one reservation, two capacity buckets, and two allocations in reverse bucket-ID insertion order;
2. reflectively invoke `findAllByReservationIdOrderByCapacityBucketIdAsc(Long)` inside `TransactionTemplate` and assert returned `capacityBucketId` values are ascending;
3. reflectively invoke `findLatestPolicyVersion(long, LocalDate)` and assert the maximum persisted version;
4. reflectively invoke `findAllByIdInForUpdate(Collection)` with reverse IDs in a worker transaction, assert the returned IDs are ascending, call `repositoryLockHeld.countDown()`, then wait on `releaseRepositoryLock` before ending that transaction;
5. after `repositoryLockHeld.await(5, SECONDS)` succeeds, issue `SELECT reservation_capacity_bucket_id FROM reservation_capacity_buckets WHERE reservation_capacity_bucket_id = ? FOR UPDATE NOWAIT` on an independent JDBC connection/transaction and assert MySQL reports the row is locked; finally count down `releaseRepositoryLock`, join the worker, and close both executor and connection.

Run:

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest.*RepositoryContract*"
```

Expected RED: the JVM test runs against MySQL and fails an AssertJ assertion that the named allocation/latest/ordered-lock repository method is absent. Compilation and Spring/Flyway startup must succeed; an infrastructure failure is not accepted as RED.

- [ ] **Step 6: Add the exact repository contracts, then observe GREEN.**

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
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest.*RepositoryContract*"
git diff --check
git diff --name-only HEAD
```

Expected: the entity tests and both real-MySQL repository contract tests pass, including ascending results and the observed write lock; exactly the five Task 3 paths are uncommitted.

- [ ] **Step 7: Commit Task 3 with exact staging.**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucket.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityAllocationRepository.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationCapacityBucketRepository.java backend/src/test/java/com/miriyum/domain/reservation/entity/ReservationCapacityBucketTest.java backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java
git commit -m "feat(reservation): add capacity restoration locks"
```

### Task 4: Implement the transactional cancellation orchestration

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationCancellationPolicyConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandResult.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java`

**Interfaces:**
- Consumes: Task 1 response, Task 2 audit/repository, Task 3 capacity contracts, existing evaluator signature, `MenuHoldService.lockForTermination/release`, `IdempotencyExecutor.execute`.
- Produces: both transactional service entries and `ReservationCancellationCommandResult` signatures listed above; existing consumer/operator GET now optionally projects audit data.

- [ ] **Task 4 gate:** Review this task as one transaction boundary. Its tests must verify call order and absence of side effects on every rejection/replay branch.

- [ ] **Step 1: Write real-MySQL actor-scoped Reservation repository tests and observe behavioral RED.**

In `ReservationMigrationTest`, add `reservationRepositoryContractsScopeAndHoldWriteLocks`. As in Task 3, locate the two exact method names on the Spring Data proxy reflectively so the pre-production test compiles. Seed reservations for two consumers and two stores. Assert the consumer method returns only `(reservationId, consumerAccountId)`, the operator method returns only `(reservationId, storeId)`, foreign consumer/store scopes return empty, and neither selector filters away a scoped `CANCELLED` row.

For locking, invoke each reflected repository method inside a worker `TransactionTemplate`; after the method returns, count down `reservationLockHeld` and wait on `releaseReservationLock`. The test waits up to five seconds for `reservationLockHeld`, then on an independent JDBC connection executes `SELECT reservation_id FROM reservations WHERE reservation_id = ? FOR UPDATE NOWAIT` and asserts MySQL reports the row lock. Release the latch and join the worker in `finally`; no sleep is permitted.

Run:

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest.*reservationRepositoryContract*"
```

Expected RED: the MySQL test executes and fails the described AssertJ method-presence assertion because the actor-scoped `FOR UPDATE` methods do not exist. Compilation, context, Flyway, and container startup must succeed.

- [ ] **Step 2: Register the evaluator, implement scoped Reservation locks, and observe repository GREEN.**

Add the existing-registry evaluator bean:

```java
@Bean
public ReservationCancellationPolicyEvaluator reservationCancellationPolicyEvaluator(
        ReservationCancellationPolicyRegistry registry) {
    return new ReservationCancellationPolicyEvaluator(registry);
}
```

Add the two explicit repository methods below. Neither query filters status, because the service must distinguish RES005 from 404 after actor scoping:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
        select reservation from Reservation reservation
        where reservation.id = :reservationId
          and reservation.consumerAccountId = :consumerAccountId
        """)
Optional<Reservation> findByIdAndConsumerAccountIdForUpdate(
        @Param("reservationId") Long reservationId,
        @Param("consumerAccountId") Long consumerAccountId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("""
        select reservation from Reservation reservation
        where reservation.id = :reservationId
          and reservation.storeId = :storeId
        """)
Optional<Reservation> findByIdAndStoreIdForUpdate(
        @Param("reservationId") Long reservationId,
        @Param("storeId") Long storeId);
```


Run the Step 1 command again. Expected: scope, status visibility, and actual write-lock assertions pass.

- [ ] **Step 3: Write actor, policy, replay, and resource tests without depending on absent production types.**

Extend `ReservationServiceTest` with audit/evaluator mocks. Before the new public result/methods exist, keep this RED test source compilable with a local helper `Object invokeCancellation(String methodName, Class<?>[] parameterTypes, Object[] arguments)`. The helper uses `ReflectionUtils.findMethod`, fails an AssertJ assertion with the missing method name when absent, invokes the method when present, and unwraps `InvocationTargetException` to its runtime cause. After production exists, the same assertions continue through the exact service signature.

Add these named tests so class filters are stable:

- `cancellationGateConsumerRunsBeforeIdempotencyAndHidesForeignReservation` verifies active-account -> idempotency -> scoped Reservation order;
- `cancellationGateOperatorOwnershipRunsBeforeIdempotency` verifies Store public management ownership and no Reservation access on failure;
- `cancellationReplayUsesStoredDataWithoutDomainReads` verifies no Reservation/capacity/MenuHold/audit calls;
- `cancellationPolicyRejectsStateBeforeEvaluator` maps non-CONFIRMED to RES005;
- `cancellationPolicyRejectsNullVersionAndNullStartBeforeEvaluator` maps each to RES006 with evaluator uncalled;
- `cancellationPolicyPassesStoredVersionActorStatusStartAndRequestedAtInOrder` covers consumer/operator and V1 before/equal/after requestedAt;
- `cancellationPolicyMapsUnknownVersionToReservation006` preserves evaluator policy rejection;
- `cancellationCapacityLocksMenuHoldBeforeOneSortedUnionLock` verifies `Reservation -> MenuHold prelock -> allocations/current candidates -> one [301,302,401] bucket lock`;
- `cancellationCapacityRestoresEachOriginalAndCurrentBucketExactlyOnce` verifies full party plus one team on two originals and one newer current bucket without allocation deletion;
- `cancellationCapacityRejectsCorruptAllocationMissingBucketUnderflowAndVersionRace` covers every invariant before Reservation/audit success;
- `cancellationCapacityReleasesOnlyPresentMenuHoldWithExactCorrelation` verifies NO_HOLD no-op and HOLD_PRESENT exact RELEASED result.

- [ ] **Step 4: Run gate/policy/resource tests and observe behavioral RED before service production.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationGate*" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationPolicy*" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationReplay*" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationCapacity*"
```

Expected RED: JUnit executes and the reflective helper fails its explicit AssertJ assertion that `cancelConsumerReservation`/`cancelStoreReservation` is absent. Compilation and existing tests must not fail. This is the observed RED for the resource algorithm; no production cancellation branch is added before it.

- [ ] **Step 5: Write audit/detail tests and observe a second behavioral RED before production.**

Add:

- `cancellationAuditUsesOneOccurredAtForReservationAndAudit` captures the saved audit and checks requested/occurred times, actor, reason, statuses, versions, and exact command;
- `cancellationAuditAcceptsWhitespaceOperatorReason` passes `" "` and requires the same value in audit/result;
- `cancellationAuditFailurePreventsSucceededOutcome` makes save fail and verifies no success result;
- `cancellationDetailStoresOnlyStatusCodeResourceAndData` captures `BusinessResult` and verifies 200/SUCCESS/RESERVATION/77/detail, with no envelope/message persistence;
- `cancellationDetailReplayDoesNotWriteAnotherAudit` uses stored JSON and verifies zero domain writes;
- `cancellationDetailGetUsesOptionalAuditAndLegacyNulls` verifies audit actor/reason when present and two nulls when absent.

Use the same reflective helper, a fixed Clock, and captors. Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationAudit*" --tests "com.miriyum.domain.reservation.service.ReservationServiceTest.cancellationDetail*"
```

Expected RED: JUnit executes and fails the explicit missing-cancellation-method assertion. Compilation and unrelated service behavior remain green. This is the observed RED for occurredAt/audit/data-only replay/future detail before their production implementation.

- [ ] **Step 6: Implement the complete service transaction and result without an intermediate fake branch.**

Create the result record and both exact service entries. Each entry validates arguments, runs only its read-only actor gate, then executes the supplied `IdempotencyCommand`. Replay decodes stored `ReservationDetail` immediately without domain reads. Fresh work locks actor scope, applies status/null/version evaluator rules, then:

1. calls `lockForTermination` and requires a non-null presence;
2. reads ordered original allocations, requires at least one, and verifies every row has the reservation's full party, one team, and stored capacity version;
3. observes current latest version/candidates for store/date/local `[start, occupancyEnd)` and rejects absent/inconsistent versions as `CAPACITY_POLICY_CHANGED`;
4. requires current IDs equal originals when versions match; otherwise treats only the newer latest overlapping candidates as publication-materialized, excluding intermediate retired versions;
5. creates a `TreeSet<Long>` union, calls `findAllByIdInForUpdate` once, compares returned/requested IDs exactly, rechecks latest version, and restores full party plus one team once per bucket;
6. calls `release(new MenuHoldReleaseCommand(reservationId, correlationId))` only for HOLD_PRESENT and validates reservation ID plus RELEASED outcome;
7. obtains `occurredAt = clock.instant()` once, calls `reservation.cancel(occurredAt)`, saves exact `recordSuccess(...)`, builds detail from audit values, and returns `BusinessResult<>(200, "SUCCESS", "RESERVATION", id, response)`;
8. lets any failure roll back status, capacity, MenuHold/inventory, audit, and idempotency finalize.

Update both GET detail methods to query `cancellationAuditRepository.findByReservationId` once and project optional actor/reason; legacy missing audit produces two nulls. `cancellationResult(IdempotentOutcome)` reconstructs nullable OffsetDateTime plus actor/reason from stored data. Preserve the existing 12-argument package-private compatibility constructor unchanged. Expand the existing 16-argument Spring `@Autowired` constructor to receive `ReservationCancellationAuditRepository` and `ReservationCancellationPolicyEvaluator` explicitly and assign both new fields; do not inject cancellation dependencies as null into the Spring path.

- [ ] **Step 7: Run the full impacted service and repository contracts GREEN, then inspect scope.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationServiceTest"
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest.*reservationRepositoryContract*"
git diff --check
git diff --name-only HEAD
```

Expected: the entire impacted service test class plus real-MySQL actor scope/lock test passes, preserving creation/query/time-policy tests; only the six Task 4 paths are uncommitted.

- [ ] **Step 8: Commit Task 4 with exact staging.**

```powershell
git add backend/src/main/java/com/miriyum/domain/reservation/config/ReservationCancellationPolicyConfig.java backend/src/main/java/com/miriyum/domain/reservation/repository/ReservationRepository.java backend/src/main/java/com/miriyum/domain/reservation/service/ReservationCancellationCommandResult.java backend/src/main/java/com/miriyum/domain/reservation/service/ReservationService.java backend/src/test/java/com/miriyum/domain/reservation/repository/ReservationMigrationTest.java backend/src/test/java/com/miriyum/domain/reservation/service/ReservationServiceTest.java
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

- [ ] **Step 1: Write source-compatible command and retry tests before the facade exists.**

Keep the RED test source compilable by loading `com.miriyum.domain.reservation.service.ReservationCancellationCommandFacade` with `Class.forName`, asserting the class and exact constructors/methods exist, constructing it reflectively, and invoking public entries reflectively. Mockito still captures the strongly typed `ReservationService` calls from Task 4.

Add named tests `commandConsumerBuildsScopedFingerprintAndCorrelation`, `commandOperatorIncludesStoreAndReservationInFingerprint`, `commandDifferentRouteOrReasonChangesFingerprint`, and `commandLongMaxOperatorCorrelationIsExactlyNinetyCharacters`. Assert exact `IdempotencyCommand` fields and compare fingerprints to `RequestFingerprint.of` over length-prefixed canonical values (`field=-1:|` for null, `field={length}:{value}|` otherwise). Assert both public methods use one captured `requestedAt` and never include reservation ID in correlation.

Add named retry tests for:

- `retryDeadlockThenTimeoutUsesThreeAttemptsAndOneRequestedAt`: MySQL 1213 then 1205 then success, delays 100/300, service called three times with the identical `Instant`;
- `retryThirdTechnicalFailureMapsCommon008WithCause`: exhaustion preserves cause;
- `retryOptimisticConflictUsesTheSameBoundedPolicy`: `ObjectOptimisticLockingFailureException` retries;
- `retryDomainValidationQueryTimeoutDataIntegrityAndUnrelatedLockDoNotRetry`: each listed exception calls service once;
- `retryInterruptionRestoresFlagAndMapsCommon008`: sleeper interruption restores the thread flag.

The reflective helper must fail with an AssertJ assertion such as `ReservationCancellationCommandFacade class is required` when the class is absent; it must not turn `ClassNotFoundException` into a compile or infrastructure error.

- [ ] **Step 2: Run command tests and observe behavioral RED.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest.command*"
```

Expected RED: JUnit runs and fails the explicit missing-facade assertion. Compilation and existing service tests succeed.

- [ ] **Step 3: Run retry classification tests and observe behavioral RED before retry production.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest.retry*"
```

Expected RED: JUnit runs and fails the same explicit missing-facade assertion before any retry implementation exists. A compile, context, or dependency failure is not accepted.

- [ ] **Step 4: Implement public entries, exact canonicalization, and bounded retry together.**

Use `@Service`, an `@Autowired` constructor `(ReservationService, Clock)`, and a package-private test constructor `(ReservationService, Clock, IntToLongFunction, RetrySleeper)`. Each public entry validates non-null key/request, obtains `Instant requestedAt = clock.instant()` exactly once, constructs `IdempotencyCommand`, reason, correlation, then passes a supplier to one private retry method. Do not put `reservationId` in correlation.

Build canonical fingerprint input with this exact field encoding and order; the two literal route templates must not be replaced with concrete path strings:

```java
private static String consumerCanonical(long reservationId, String reason) {
    StringBuilder canonical = new StringBuilder();
    appendCanonical(canonical, "method", "POST");
    appendCanonical(canonical, "route",
            "/api/v1/reservations/{reservationId}/cancellations");
    appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
    appendCanonical(canonical, "reason", reason);
    return canonical.toString();
}

private static String operatorCanonical(long storeId, long reservationId, String reason) {
    StringBuilder canonical = new StringBuilder();
    appendCanonical(canonical, "method", "POST");
    appendCanonical(canonical, "route",
            "/api/v1/store-operator/stores/{storeId}/reservations/{reservationId}/cancellations");
    appendCanonical(canonical, "storeId", String.valueOf(storeId));
    appendCanonical(canonical, "reservationId", String.valueOf(reservationId));
    appendCanonical(canonical, "reason", reason);
    return canonical.toString();
}

private static void appendCanonical(StringBuilder target, String field, String value) {
    target.append(field).append('=');
    if (value == null) {
        target.append("-1:");
    } else {
        target.append(value.length()).append(':').append(value);
    }
    target.append('|');
}
```

Use `RequestFingerprint.of(consumerCanonical(...))` or `RequestFingerprint.of(operatorCanonical(...))` in `new IdempotencyCommand("consumer"|"store-operator", actorId, "RESERVATION_CANCEL", key.value(), fingerprint)`. Build correlation with `"reservation-cancel:" + namespace + ":" + actorId + ":" + key.value()` and pass the unchanged normalized key and reason to the exact Task 4 service entry.

Copy the proven classification shape from `ReservationCreationCommandFacade`, adding optimistic conflicts explicitly. Keep delays `ThreadLocalRandom.current().nextLong(100L, 201L)` and `nextLong(300L, 501L)`, and sleep outside service transactions.

- [ ] **Step 5: Run all facade tests GREEN.**

Run:

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.service.ReservationCancellationCommandFacadeTest"
git diff --check
git diff --name-only HEAD
```

Expected: all facade tests pass; exactly the two Task 5 paths are uncommitted.

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

Also test missing key -> COMMON_003/facade uncalled, malformed key -> COMMON_004, overlong reason/unknown field -> COMMON_002, omitted reason accepted, unauthenticated -> AUTH_001, operator token -> AUTH_004, and `RESERVATION_001/005/006` passthrough with their 404/409 statuses. Add an authenticated nonapproved method on the cancellation path expecting AUTH_006. Keep create POST, detail GET, and unknown subpath deny tests.

- [ ] **Step 2: Write failing operator MockMvc tests.**

Import the Reservation-owned operator reservation security chain alongside the existing Store management chain. Test valid call propagation `(operatorId=33, storeId=22, reservationId=77, key, reason)`, HTTP 200/detail, missing/malformed key, missing/empty/501 reason, whitespace reason accepted, unknown field, unauthenticated AUTH_001, consumer token AUTH_004, Store `STORE_003` passthrough, in-store `RESERVATION_001`, and `RESERVATION_005/006` passthrough. Add an authenticated POST to `/reservations/77/unknown` and a nonapproved method to the cancellation path expecting AUTH_006.

- [ ] **Step 3: Run both controller tests and observe RED.**

```powershell
backend\gradlew.bat -p backend test --tests "com.miriyum.domain.reservation.controller.ReservationControllerTest" --tests "com.miriyum.domain.reservation.controller.StoreReservationControllerTest"
```

Expected RED: the consumer cancellation path is rejected by the existing deny-all rule and the operator path is unmapped or not protected by the Reservation-owned fail-closed chain; the already-existing Task 5 facade mock is never called. Compilation succeeds, so the failure demonstrates the missing controller/security behavior rather than a missing dependency.

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

Operator, under the existing class mapping `/api/v1/store-operator/stores/{storeId}/reservations`, adds:

```java
@PostMapping("/{reservationId}/cancellations")
public ResponseEntity<ApiResponse<ReservationDetailResponse>> cancelReservation(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable @Positive long storeId,
        @PathVariable long reservationId,
        @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
        @Valid @RequestBody StoreCancellationRequest request) {
    ReservationCancellationCommandResult result =
            reservationCancellationCommandFacade.cancelByStoreOperator(
                    principal.accountId(), storeId, reservationId,
                    IdempotencyKey.parse(rawKey), request);
    return ResponseEntity.status(result.httpStatus())
            .body(ApiResponse.success("예약이 취소되었습니다.", result.data()));
}
```

Do not annotate `reservationId` with `@Positive`, because actor-scoped absence must use hidden `RESERVATION_001`; do not catch or remap `ServiceException` in either controller.

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

### Task 7: Run fresh MySQL transaction, replay, restoration, and contention verification

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/reservation/service/ReservationCancellationIT.java`

**Interfaces:**
- Consumes: production facade/service/repositories, real Global idempotency, Store/consumer account gates, real MenuHold release, V26.
- Produces: class-filtered MySQL evidence tagged `integration` and exactly one shard tag `integration-shard-b`.

- [ ] **Task 7 gate:** Production behavior already completed RED/GREEN in Tasks 2-6. This task adds fresh black-box MySQL verification whose first run is expected to PASS; any failure is `FAIL`, never acceptable RED. Use `mysql:8.0.40`, application `JdbcTemplate`/`TransactionTemplate`/`DataSource` for test actions, a dedicated root JDBC connection only for read-only `performance_schema` wait observation, no sleep-only contention proof, bounded executor shutdown, and database snapshots around failures.

- [ ] **Step 1: Create the tagged IT fixture and no-menu replay verification.**

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

- [ ] **Step 2: Add successful store-operator atomic cancellation verification.**

Create a fixture whose authenticated operator currently manages the reservation's Store and whose confirmed reservation has two capacity buckets plus a real CONFIRMED MenuHold/inventory allocation. Call:

```java
ReservationCancellationCommandResult first = facade.cancelByStoreOperator(
        scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key(2),
        new StoreCancellationRequest(" "));
ReservationCancellationCommandResult replay = facade.cancelByStoreOperator(
        scenario.operatorId(), scenario.storeId(), scenario.reservationId(), key(2),
        new StoreCancellationRequest(" "));
```

Assert HTTP 200, equal first/replay detail, `cancelledBy == "STORE_OPERATOR"`, reason remains exactly `" "`, Reservation CANCELLED, both capacity buckets restored once, MenuHold RELEASED, inventory and ledger restored once, and one audit with actor ID/type/reason and command `reservation-cancel:store-operator:{operatorId}:{key}`. Query `idempotency_commands` and assert one SUCCEEDED row with namespace `store-operator`, the operator account ID, type `RESERVATION_CANCEL`, normalized key, and data-only payload; the audit command and MenuHold restore ledger operation ID must equal the same correlation.

- [ ] **Step 3: Add concrete resource, policy, and rollback verification.**

Add tests with explicit DB assertions for:

- `HOLD_PRESENT` and `NO_HOLD` branches independently;
- two original buckets and a republished newer current bucket union, with allocations unchanged and no intermediate retired-bucket decrement;
- legacy `CONFIRMED` null cancellation version and null startAt -> RES006 with identical before/after snapshot;
- different key after success -> RES005; same key/different reason -> COMMON_007;
- consumer foreign reservation -> RES001 and wrong operator management ownership -> Store public 403/404;
- temporary MySQL trigger failures at MenuHold RELEASED update, audit insert, and idempotency SUCCEEDED update, plus guarded capacity underflow. Every failure leaves Reservation, timestamps, capacity, MenuHold/inventory/ledger, audit count, and idempotency success fields equal to the pre-command snapshot.

Create/drop each uniquely named trigger inside `try/finally`. A unique/audit conflict is failure, not replay success.

- [ ] **Step 4: Implement deterministic same-key idempotency lock contention with test-owned JDBC.**

Add `sameKeyContentionBlocksAtIdempotencyThenProducesOneEffectAndEqualResponses`. Use an autowired `TransactionTemplate`, `JdbcTemplate`, and `DataSource` plus latches `idempotencyLockHeld`, `releaseIdempotencyLock`, `workersReady`, and `startWorkers`.

The holder executor starts a transaction and uses the transaction-bound `JdbcTemplate` to execute the following insert without committing, then reads `SELECT CONNECTION_ID()`, counts down `idempotencyLockHeld`, waits for release, and marks its transaction rollback-only:

```sql
INSERT INTO idempotency_commands (
    principal_namespace, principal_id, command_type, idempotency_key,
    request_fingerprint, processing_status, created_at, updated_at
) VALUES (
    'consumer', ?, 'RESERVATION_CANCEL', ?, ?,
    'PROCESSING', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6)
)
```

Bind the fixture's positive consumer account ID, normalized key, and exact facade fingerprint. After the holder latch, start two facade workers with the same key/fingerprint; each counts down `workersReady` immediately before the facade call and waits at `startWorkers`.

After both workers are released, prove actual DB blocking without sleep by all three observations:

1. `future.get(250, MILLISECONDS)` for each worker throws `TimeoutException`;
2. `awaitBlockingWaits(holderConnectionId, "idempotency_commands", "uk_idempotency_commands", 2)` returns before its five-second deadline;
3. Reservation status/capacity/audit remain unchanged while the holder transaction is open.

The application Testcontainers user does not have the repository's required `performance_schema.data_lock_waits`, `data_locks`, and `threads` visibility. Keep the autowired application `DataSource` for holder/workers and domain assertions, but implement the test-only monitor with the same root-connection pattern as `ReservationCapacityPublicationIT`. Add imports for `java.sql.Connection`, `DriverManager`, `PreparedStatement`, `ResultSet`, and `SQLException`, then add this complete helper:

```java
private void awaitBlockingWaits(
        long holderConnectionId,
        String tableName,
        String indexName,
        int expectedWaits
) {
    long deadlineNanos = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    int lastObservedCount = 0;
    try (Connection monitoringConnection = DriverManager.getConnection(
            MYSQL.getJdbcUrl(), "root", MYSQL.getPassword())) {
        monitoringConnection.setReadOnly(true);
        try (PreparedStatement statement = monitoringConnection.prepareStatement("""
                SELECT COUNT(*)
                FROM performance_schema.data_lock_waits AS wait_edge
                JOIN performance_schema.data_locks AS blocking_lock
                  ON blocking_lock.ENGINE = wait_edge.ENGINE
                 AND blocking_lock.ENGINE_LOCK_ID = wait_edge.BLOCKING_ENGINE_LOCK_ID
                JOIN performance_schema.threads AS blocking_thread
                  ON blocking_thread.THREAD_ID = blocking_lock.THREAD_ID
                WHERE blocking_thread.PROCESSLIST_ID = ?
                  AND blocking_lock.OBJECT_SCHEMA = DATABASE()
                  AND blocking_lock.OBJECT_NAME = ?
                  AND blocking_lock.INDEX_NAME = ?
                """)) {
            statement.setLong(1, holderConnectionId);
            statement.setString(2, tableName);
            statement.setString(3, indexName);
            while (System.nanoTime() < deadlineNanos) {
                try (ResultSet resultSet = statement.executeQuery()) {
                    if (!resultSet.next()) {
                        throw new AssertionError("lock wait count query returned no row");
                    }
                    lastObservedCount = resultSet.getInt(1);
                }
                if (lastObservedCount >= expectedWaits) {
                    return;
                }
                Thread.onSpinWait();
            }
        }
    } catch (SQLException exception) {
        throw new IllegalStateException(
                "unable to observe MySQL lock waits with the root monitoring connection",
                exception);
    }
    throw new AssertionError(
            "expected " + expectedWaits + " lock waits blocked by connection "
                    + holderConnectionId + " on " + tableName + "." + indexName
                    + " but last observed " + lastObservedCount);
}
```

The `(ENGINE_LOCK_ID, ENGINE)` pair identifies a performance-schema lock, so both join predicates are mandatory. The root connection is opened only inside this helper, marked read-only, and closed with its `PreparedStatement`/each `ResultSet` through try-with-resources. Do not run holder inserts, Reservation locks, facade calls, or assertions through this monitoring connection. This wait-edge query plus both timed `Future` observations proves contention; executor readiness, latches, or elapsed sleep alone do not.

Count down `releaseIdempotencyLock`; the holder rolls back, one worker claims/executes and the other replays. Assert equal responses, one successful idempotency row, one audit, one Reservation transition, and one capacity/MenuHold restoration. In `finally`, release every latch, call `shutdownNow()`, and require `awaitTermination(5, SECONDS)`.

- [ ] **Step 5: Implement deterministic different-key Reservation-row contention.**

Add `differentKeyContentionBlocksAtReservationThenProducesOneSuccessAndReservation005`. The holder transaction uses JDBC `SELECT reservation_id FROM reservations WHERE reservation_id = ? FOR UPDATE`, records `CONNECTION_ID()`, counts down `reservationLockHeld`, waits for `releaseReservationLock`, then commits without mutation.

Start two facade workers with distinct keys and the same reservation. Both can claim distinct idempotency rows, then must wait on the held Reservation row. Prove both waits with worker `Future.get(250, MILLISECONDS)` timeouts and the same root-monitoring helper call `awaitBlockingWaits(holderConnectionId, "reservations", "PRIMARY", 2)`. While held, assert both idempotency rows are not externally committed and resources are unchanged. Release the holder; assert exactly one HTTP 200 and one `ServiceException` with RES005, one audit, one transition, and one capacity/MenuHold restoration. Apply the same `finally` cleanup/termination rules.

- [ ] **Step 6: Run the class-filtered IT fresh; first PASS is required.**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT" --rerun-tasks
git diff --check
git diff --name-only HEAD
```

Expected: the entire class passes on its first execution after creation and only `ReservationCancellationIT.java` is uncommitted. A compilation, context, migration, assertion, timeout, or Docker failure is `FAIL`; return to the owning earlier task, add/confirm its behavioral RED, fix minimally, rerun that task, then rerun this verification.

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

Expected: all selected tests pass. This intentionally gives fast diagnostics for the impacted `ReservationServiceTest` and cancellation slices before the mandatory full build in Step 3.

- [ ] **Step 2: Run fresh class-filtered MySQL verification.**

```powershell
backend\gradlew.bat -p backend integrationTest --tests "com.miriyum.domain.reservation.repository.ReservationMigrationTest" --tests "com.miriyum.domain.reservation.service.ReservationCancellationIT" --rerun-tasks
```

Expected: both MySQL classes pass. This class-filtered run isolates cancellation failures before the mandatory full build; do not replace it with `integrationTestShardB`.

- [ ] **Step 3: Run a fresh full build at the current HEAD and record its exit code and test totals.**

Run from the repository root in one PowerShell session:

```powershell
backend\gradlew.bat -p backend build --rerun-tasks
$buildExitCode = $LASTEXITCODE
$resultFiles = Get-ChildItem backend/build/test-results -Recurse -Filter 'TEST-*.xml'
$testCount = 0
$failureCount = 0
$errorCount = 0
$skippedCount = 0
foreach ($resultFile in $resultFiles) {
    [xml]$suiteDocument = Get-Content -Raw $resultFile.FullName
    $testCount += [int]$suiteDocument.testsuite.tests
    $failureCount += [int]$suiteDocument.testsuite.failures
    $errorCount += [int]$suiteDocument.testsuite.errors
    $skippedCount += [int]$suiteDocument.testsuite.skipped
}
[pscustomobject]@{
    ExitCode = $buildExitCode
    ResultFiles = $resultFiles.Count
    Tests = $testCount
    Failures = $failureCount
    Errors = $errorCount
    Skipped = $skippedCount
}
if ($buildExitCode -ne 0) {
    throw "Full build failed with exit code $buildExitCode"
}
```

Expected: `ExitCode=0`, `Failures=0`, and `Errors=0`; record the displayed result-file, test, and skipped counts. `build` executes the complete non-integration unit/slice surface and, through this repository's `check` dependency, the full `integrationTest` surface at current HEAD. This is deliberately broader than Steps 1-2 and includes unrelated existing tests; a failure anywhere is a release blocker, not an acceptable RED.

- [ ] **Step 4: Stop Gradle workers and record any remaining process.**

```powershell
backend\gradlew.bat -p backend --stop
Get-Process java -ErrorAction SilentlyContinue
```

Expected: Gradle reports stopped daemons. Do not terminate Java processes that are not proven to belong to these tests.

- [ ] **Step 5: Verify formatting, migration slot, and exact 32-path equality.**

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

- [ ] **Step 6: Perform the plan self-review required by `writing-plans`.**

Run:

```powershell
rg -n "T[B]D|T[O]DO|implement l[a]ter|fill in d[e]tails|Similar to T[a]sk|add appr[o]priate|Write tests for the ab[o]ve" docs/superpowers/plans/2026-08-07-reservation-cancellation.md
rg -n "/\*[[:space:]]|omitted f[i]eld|remaining f[i]eld|same as T[a]sk|same as ab[o]ve|implementation om[i]tted|body om[i]tted|placeholder sk[e]leton" docs/superpowers/plans/2026-08-07-reservation-cancellation.md
rg -n "cancelConsumerReservation|cancelStoreReservation|cancelByConsumer|cancelByStoreOperator|recordSuccess|findAllByIdInForUpdate|findLatestPolicyVersion" docs/superpowers/plans/2026-08-07-reservation-cancellation.md
```

Expected: placeholder scan returns no matches; interface scan shows consistent definitions/usages. Manually map every design TDD bullet to Tasks 1-7 and every Issue allowlist path to the File Responsibility Map.

- [ ] **Step 7: Dispatch an independent code reviewer.**

Give a fresh reviewer Issue #51, the approved design, this plan, and `origin/dev...HEAD`. Require findings only for correctness, authorization, transaction/lock order, idempotency/replay, data integrity, security, migration compatibility, and missing tests. The reviewer must verify no cross-domain Entity/Repository access and no allowlist escape.

Expected: no Important finding. For an Important finding, return to its owning task, write a reproducing RED test, apply the minimum fix, rerun only impacted focused tests plus the relevant MySQL class, commit without amend, and request re-review.

- [ ] **Step 8: Record the final local handoff without publishing.**

Report exact commit range; every fresh command and exit code; focused and full-build test counts; 32/32 path equality; migration version; reviewer verdict; and tracked/staged status. State that the full build covered the complete unit/slice and full `integrationTest` surfaces, while standalone shard tasks were not additionally duplicated. Do not push or create/update a PR unless the user separately authorizes publication.
