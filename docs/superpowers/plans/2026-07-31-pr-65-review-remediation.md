# PR #65 Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR #65의 미해결 리뷰 7건을 확정 계약에 맞게 수정하고 MySQL 동시성·유일성 회귀 테스트로 검증한다.

**Architecture:** 공개 경계에서는 문자열 ID와 완전한 모드 입력을 강제하고, `Store` aggregate는 폐점 종단 전이와 private 생성 규칙을 소유한다. 쓰기 Service는 인증·소유권을 먼저 확인한 뒤 멱등 기록을 선점하고, 신규 명령 supplier 안에서 MySQL `SELECT ... FOR UPDATE`로 최신 매장을 잠근 후 카탈로그 검증과 PATCH를 수행한다.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Data JPA `PESSIMISTIC_WRITE`, MySQL 8.0.40, Flyway, JUnit 5, AssertJ, MockMvc, Mockito, Testcontainers

## Global Constraints

- C-006: 멱등 기록을 업무 자원보다 먼저 선점하고 같은 키·지문은 최초 결과를 재생한다.
- C-007: 경합 명령은 `READ_COMMITTED`, 5초 트랜잭션과 MySQL 행 잠금을 사용한다.
- C-008: 공개 JSON 도메인 ID는 문자열이다.
- OPER-009: 매장 운영자는 `CLOSED`를 되돌릴 수 없고 동일 사업자번호 재개는 플랫폼 운영자 복구 범위다.
- Redis, Valkey, 분산 락, Spring Retry와 새 외부 라이브러리를 추가하지 않는다.
- GitHub 리뷰 답글과 thread resolve는 사용자 요청 없이 수행하지 않는다.

---

### Task 1: Public ID and Complete Store Modes Contract

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/dto/ManagedStoreResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreModesRequest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/dto/ManagedStoreResponseTest.java`
- Modify: affected store test fixtures that construct `ManagedStoreResponse`

**Interfaces:**
- Consumes: internal `Store.getId(): Long`
- Produces: `ManagedStoreResponse.storeId(): String`; `StoreModesRequest(Boolean, Boolean, Boolean)` with `@NotNull` fields

- [ ] **Step 1: Write failing response and validation tests**

Add controller assertions that `$.data.storeId` is the literal string `"7"`. Add parameterized or three focused create tests that omit each of `reservationEnabled`, `menuHoldEnabled`, and `pickupEnabled` and assert HTTP 400 with `$.code == "COMMON_001"`. Add the equivalent PATCH validation coverage using a request with `modes` present and one field missing.

Add a DTO test with a reflected ID beyond JavaScript's safe integer:

```java
ReflectionTestUtils.setField(store, "id", 9_007_199_254_740_993L);
assertThat(ManagedStoreResponse.from(store).storeId())
        .isEqualTo("9007199254740993");
```

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.controller.StoreControllerTest" `
  --tests "com.miriyum.domain.store.core.dto.ManagedStoreResponseTest"
```

Expected: string-ID assertions and missing-mode-field requests fail against the current `Long`/primitive implementation.

- [ ] **Step 3: Implement the minimal DTO contract**

Change the response field and mapping:

```java
public record ManagedStoreResponse(String storeId, ...) {
    public static ManagedStoreResponse from(Store store) {
        return new ManagedStoreResponse(String.valueOf(store.getId()), ...);
    }
}
```

Change each request field:

```java
public record StoreModesRequest(
        @NotNull Boolean reservationEnabled,
        @NotNull Boolean menuHoldEnabled,
        @NotNull Boolean pickupEnabled
) {
}
```

Update test fixtures to pass string response IDs.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Task 1 command again and require zero failures.

---

### Task 2: Terminal Closure, Business Number Ownership, and Entity Construction

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java`
- Modify: `backend/src/main/resources/db/migration/V8__create_stores.sql`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/entity/StoreTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/repository/StoreRepositoryIT.java`

**Interfaces:**
- Consumes: `Store.update(..., OperationStatus operationStatus)`
- Produces: private construction boundary; `CLOSED` reversal raises `ServiceException(StoreErrorCode.STORE_STATE_CONFLICT)`; business number remains globally unique in the 1차 MVP schema

- [ ] **Step 1: Write failing state and uniqueness tests**

Add entity tests for:

```java
store.update(..., OperationStatus.CLOSED);

assertThatThrownBy(() ->
        store.update("변경되면 안 됨", ..., OperationStatus.OPEN))
        .isInstanceOf(ServiceException.class)
        .extracting(exception -> ((ServiceException) exception).getErrorCode())
        .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);

assertThat(store.getName()).isEqualTo("기존 이름");
assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.CLOSED);
```

Repeat the rejection for `CLOSED -> TEMPORARILY_CLOSED`. Keep coverage for `OPEN <-> TEMPORARILY_CLOSED` and transitions from either state to `CLOSED`.

Replace `closedStoreReleasesActiveBusinessNumber` with a test that closes the first store and expects a second operator's same-number insert to fail with `uk_stores_active_business_number`.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.entity.StoreTest" `
  --tests "com.miriyum.domain.store.core.repository.StoreRepositoryIT.closedStoreKeepsBusinessNumberOwnership"
```

Expected: reversal tests and closed-store uniqueness test fail.

- [ ] **Step 3: Implement state guard, constructor, and schema**

Add an explicit private constructor for the required persisted inputs. Keep the JPA protected no-args constructor. Make `create()` compute `PickupEligibility`, validate pickup, call the private constructor, and set only `verificationStatus`, `operationStatus`, and `pickupEligibility` defaults.

Before any mutation in `update()`, validate the requested operation status. Permit same-state updates, `OPEN <-> TEMPORARILY_CLOSED`, and either non-terminal state to `CLOSED`; reject a different target when current status is `CLOSED` with `StoreErrorCode.INVALID_STATE`.

In V8 remove the generated `active_business_registration_number` column and apply the existing constraint name directly:

```sql
CONSTRAINT uk_stores_active_business_number
    UNIQUE (business_registration_number)
```

Update integration test SQL that counted the generated column to query `business_registration_number`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run Task 2 tests and require zero failures.

---

### Task 3: Idempotency Replay Before Mutable Catalog Validation

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`

**Interfaces:**
- Consumes: `IdempotencyExecutor.execute(command, businessWork)`
- Produces: catalog validation executes only inside `businessWork`; account and target ownership remain checked before execution

- [ ] **Step 1: Write failing supplier-boundary tests**

Add create and update tests where `IdempotencyExecutor` returns a stored outcome without calling its supplier. Assert that account and update ownership checks occur, while these do not:

```java
then(catalogPolicy).shouldHaveNoInteractions();
then(storeRepository).should(never()).saveAndFlush(any());
```

Add a different-fingerprint simulation where `execute()` throws `COMMON_007` before invoking the supplier and assert catalog validation is not called.

- [ ] **Step 2: Run tests and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreServiceTest"
```

Expected: current pre-executor catalog calls violate the new assertions.

- [ ] **Step 3: Move only mutable validation into business work**

For create, keep `operatorAccountService.getMe()` and command construction before `execute()`, then call `catalogPolicy.validate(...)` as the first operation inside the supplier.

For update, keep existence and ownership verification before command execution. Recompute the effective category and tags from the locked current store inside the supplier, then validate and update. Do not move authentication or authorization behind replay.

- [ ] **Step 4: Run service tests and verify GREEN**

Run Task 3 tests and require zero failures.

---

### Task 4: MySQL Pessimistic Store Lock and Concurrent PATCH Preservation

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/repository/StoreRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/repository/StoreRepositoryIT.java`

**Interfaces:**
- Produces:
  - `StoreRepository.findOperatorAccountIdById(long): Optional<Long>`
  - `StoreRepository.findByIdForUpdate(long): Optional<Store>` with `PESSIMISTIC_WRITE`

- [ ] **Step 1: Write the failing concurrent service integration test**

Autowire `StoreService` in `StoreRepositoryIT` and spy the real `IdempotencyExecutor` only to place a two-party barrier immediately before `execute()` calls its real method. Launch two updates with different valid idempotency keys:

```java
StoreUpdateRequest rename = new StoreUpdateRequest(
        "새 이름", null, null, null, null, null, null, null);
StoreUpdateRequest relocate = new StoreUpdateRequest(
        null, null, null, "새 주소", null, null, null, null);
```

After both futures complete, clear persistence context and assert the final row contains both `"새 이름"` and `"새 주소"`. The barrier ensures the old implementation has loaded the same stale state in both threads before either business supplier continues.

- [ ] **Step 2: Run the integration test and verify RED**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.repository.StoreRepositoryIT.concurrentDifferentFieldUpdatesPreserveBothChanges"
```

Expected: one field is lost on the unlocked implementation.

- [ ] **Step 3: Add repository queries and lock after idempotency claim**

Add:

```java
@Query("select store.storeOperatorAccountId from Store store where store.id = :storeId")
Optional<Long> findOperatorAccountIdById(@Param("storeId") long storeId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select store from Store store where store.id = :storeId")
Optional<Store> findByIdForUpdate(@Param("storeId") long storeId);
```

Use the scalar owner query for the pre-executor authorization check so no stale `Store` entity enters the persistence context. Inside the new-command supplier, call `findByIdForUpdate`, recheck ownership, derive effective catalog values from the locked latest Entity, validate, update, and flush.

- [ ] **Step 4: Update unit expectations**

Update `StoreServiceTest` repository stubs and verifications so update replay uses the owner scalar query but does not lock, while fresh update locks and saves exactly once.

- [ ] **Step 5: Run focused service and integration tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.service.StoreServiceTest" `
  --tests "com.miriyum.domain.store.core.repository.StoreRepositoryIT"
```

Require zero failures and confirm the Testcontainers test executed.

---

### Task 5: Full Regression and PR Scope Verification

**Files:**
- Verify all changed production, test, migration, design, and plan files

**Interfaces:**
- Produces: evidence that all seven review threads are addressed without unrelated changes

- [ ] **Step 1: Run the complete store core suite**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.*"
```

- [ ] **Step 2: Run the complete backend build**

```powershell
.\gradlew.bat clean build
```

- [ ] **Step 3: Run diff and whitespace checks**

From the repository worktree root:

```powershell
git diff --check origin/dev...HEAD
git status --short
git diff --stat origin/dev...HEAD
```

- [ ] **Step 4: Review each requirement against the diff**

Confirm:

1. `storeId` is a string and mode omissions return 400.
2. `CLOSED` reversal is rejected without partial mutation.
3. replay and key conflict bypass mutable catalog validation.
4. idempotency claim precedes the store row lock.
5. concurrent distinct-field PATCH preserves both fields.
6. closed-store business number remains unavailable to self-service registration.
7. `Store` uses a private constructor plus static factory.

- [ ] **Step 5: Commit and push**

Stage only the files listed in this plan, commit with a focused review-remediation message, and push `codex/33-store-core`. Do not reply to or resolve GitHub review threads without a separate user request.
