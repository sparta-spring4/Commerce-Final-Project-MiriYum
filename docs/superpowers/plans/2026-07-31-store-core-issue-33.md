# Store Core Issue #33 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 인증된 매장 운영자가 자신의 매장을 멱등하게 등록·조회·수정하고 다른 도메인이 같은 중앙 권한 검증 계약을 사용하게 한다.

**Architecture:** 기존 `com.miriyum.domain.store` bounded context 아래에 `core` 패키지를 추가해 #31 catalog와 한 도메인으로 유지한다. `Store` aggregate가 상태·픽업 자격 불변식을 소유하고, `StoreService`가 운영자 계정 활성 상태, catalog, 멱등 실행, 권한 판정을 조정하며, MySQL 제약이 활성 사업자등록번호 동시 귀속을 최종 방어한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC, Spring Security, Spring Data JPA, Flyway, MySQL 8.0.40, JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers

## Global Constraints

- Issue #33만 구현하며 #34 영업시간, #35 메뉴, #36 공개 검색, #37·#38 프론트엔드는 포함하지 않는다.
- `miriyum.identity-verification.dev-stub-enabled` 기본값과 `MIRIYUM_IDENTITY_VERIFICATION_STUB_ENABLED` 설정은 변경하지 않는다.
- 작업은 최신 `dev`와 PR #60 운영자 인증 계약을 결합한 `codex/33-store-core` 전용 worktree에서 수행한다.
- PR #60 병합본의 `V6__create_store_operator_accounts.sql`과 후속 `V7__widen_consumer_password_hash.sql`을 보존하고, #33 migration은 V8을 사용한다.
- 요청의 운영자 ID·role은 받지 않고 `AuthenticatedPrincipal(TokenNamespace.STORE_OPERATOR, accountId)`만 사용한다.
- 인증·catalog·멱등 도메인의 Entity나 Repository를 직접 참조하지 않고 공개 Service·DTO만 사용한다.
- 매장 등록·수정은 `IdempotencyExecutor`와 동일한 `READ_COMMITTED` 트랜잭션에서 결과를 확정한다.
- 사업자등록번호 동시 귀속은 MySQL 유일 제약으로 한 건만 성공시킨다.
- `OTHER`와 `pickupEnabled=true` 조합은 `STORE_008`로 전체 거절하고 값을 자동 보정하지 않는다.
- 다른 운영자의 매장 접근은 `STORE_003` 403이며 거짓 404를 반환하지 않는다.
- 현재 작업 폴더의 미추적 파일과 다른 Issue 파일은 stage·commit하지 않는다.

## Execution Preflight: Isolated Dependency Base

이 절은 #33 제품 커밋이 아니라 격리 worktree를 만들 때 수행할 로컬 선행 기준이다. 실행 시 먼저 `superpowers:using-git-worktrees`를 사용한다.

1. 최신 원격을 가져오고 `origin/dev`에서 로컬 선행 브랜치를 만든다.
2. `origin/feature/auth-storeoperator`를 로컬 선행 브랜치에 병합한다.
3. 운영자 migration을 V6으로 정렬하고 최신 `dev`의 V5 요청 제한 migration을 보존한다.
4. 최신 `dev`의 등급별 `miriyum.rate-limit.*` 설정과 MySQL `RateLimiter` 구현을 선택한다.
5. 다음 파일이 동시에 존재하는지 확인한다.

```text
backend/src/main/resources/db/migration/V4__create_idempotency_commands.sql
backend/src/main/resources/db/migration/V5__create_rate_limit_windows.sql
backend/src/main/resources/db/migration/V6__create_store_operator_accounts.sql
backend/src/main/java/com/miriyum/domain/storeoperator/service/StoreOperatorAccountService.java
backend/src/main/java/com/miriyum/domain/auth/jwt/AuthenticatedPrincipal.java
```

6. 로컬 선행 기준에서 `backend/gradlew.bat test`를 통과시킨 뒤 `codex/33-store-core` 브랜치와 worktree를 만든다.
7. PR #60이 `dev`에 병합되면 #33 브랜치를 최신 `dev`에 재배치하고 로컬 선행 커밋이 #33 diff에서 사라졌는지 확인한다.

---

## File Structure

### New production files

```text
backend/src/main/java/com/miriyum/domain/store/error/StoreErrorCode.java
backend/src/main/java/com/miriyum/domain/store/core/config/StoreManagementSecurityConfig.java
backend/src/main/java/com/miriyum/domain/store/core/controller/StoreController.java
backend/src/main/java/com/miriyum/domain/store/core/dto/ManagedStoreResponse.java
backend/src/main/java/com/miriyum/domain/store/core/dto/StoreCreateRequest.java
backend/src/main/java/com/miriyum/domain/store/core/dto/StoreModesRequest.java
backend/src/main/java/com/miriyum/domain/store/core/dto/StoreUpdateRequest.java
backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java
backend/src/main/java/com/miriyum/domain/store/core/enums/BusinessType.java
backend/src/main/java/com/miriyum/domain/store/core/enums/OperationStatus.java
backend/src/main/java/com/miriyum/domain/store/core/enums/PickupEligibility.java
backend/src/main/java/com/miriyum/domain/store/core/enums/Region.java
backend/src/main/java/com/miriyum/domain/store/core/enums/VerificationStatus.java
backend/src/main/java/com/miriyum/domain/store/core/repository/StoreRepository.java
backend/src/main/java/com/miriyum/domain/store/core/service/StoreCatalogPolicy.java
backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandFingerprint.java
backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandResult.java
backend/src/main/java/com/miriyum/domain/store/core/service/StoreManagementView.java
backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java
backend/src/main/resources/db/migration/V8__create_stores.sql
```

### New test files

```text
backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java
backend/src/test/java/com/miriyum/domain/store/core/entity/StoreTest.java
backend/src/test/java/com/miriyum/domain/store/core/repository/StoreRepositoryIT.java
backend/src/test/java/com/miriyum/domain/store/core/service/StoreCatalogPolicyTest.java
backend/src/test/java/com/miriyum/domain/store/core/service/StoreCommandFingerprintTest.java
backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java
```

`Store`는 영속 상태와 불변식만 담당한다. `StoreCatalogPolicy`는 #31 `CatalogService` 결과를 STORE 오류로 매핑한다. `StoreCommandFingerprint`는 tag 순서와 UUID 대소문자 차이가 같은 업무 요청을 다른 요청으로 만들지 않도록 정규 문자열을 만든다. `StoreService`는 트랜잭션, 활성 계정 확인, 권한, catalog, 멱등 실행을 조정한다.

---

### Task 1: Store Errors, Enums, and Aggregate Invariants

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/error/StoreErrorCode.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/enums/BusinessType.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/enums/OperationStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/enums/PickupEligibility.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/enums/Region.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/enums/VerificationStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/entity/StoreTest.java`

**Interfaces:**
- Consumes: `BaseEntity`, `ServiceException`, `ErrorCode`
- Produces: `Store.create(...)`, `Store.update(...)`, `Store.requireManagedBy(long)`, 상태 enum과 getter

- [ ] **Step 1: Write failing aggregate tests**

```java
class StoreTest {

    @Test
    void cafeIsApprovedOpenAndPickupEligible() {
        Store store = Store.create(
                11L, "1234567890", BusinessType.CAFE, "미리윰",
                "설명", Region.SEOUL, "서울시 중구", "CAFE_BAKERY",
                Set.of("DATE"), true, true, true);

        assertThat(store.getVerificationStatus()).isEqualTo(VerificationStatus.APPROVED);
        assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.OPEN);
        assertThat(store.getPickupEligibility()).isEqualTo(PickupEligibility.ELIGIBLE);
    }

    @Test
    void otherCannotEnablePickup() {
        assertThatThrownBy(() -> Store.create(
                11L, "1234567890", BusinessType.OTHER, "미리윰",
                "", Region.SEOUL, "서울시 중구", "ETC",
                Set.of(), true, false, true))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.PICKUP_NOT_ELIGIBLE);
    }

    @Test
    void otherOperatorIsForbidden() {
        Store store = Store.create(
                11L, "1234567890", BusinessType.BAKERY, "미리윰",
                "", Region.BUSAN, "부산시 중구", "CAFE_BAKERY",
                Set.of(), true, false, false);

        assertThatThrownBy(() -> store.requireManagedBy(12L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.ACCESS_DENIED);
    }
}
```

- [ ] **Step 2: Run the focused test and verify failure**

Run from `backend`:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.entity.StoreTest"
```

Expected: compilation fails because `Store`, enums, and `StoreErrorCode` do not exist.

- [ ] **Step 3: Implement the error contract and enums**

`StoreErrorCode` must implement these exact mappings:

```java
STORE_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_001", "매장을 찾을 수 없습니다."),
BUSINESS_NUMBER_CONFLICT(HttpStatus.CONFLICT, "STORE_002", "이미 등록된 사업자등록번호입니다."),
ACCESS_DENIED(HttpStatus.FORBIDDEN, "STORE_003", "대상 매장의 대표 운영자가 아닙니다."),
CATALOG_CODE_INVALID(HttpStatus.BAD_REQUEST, "STORE_004", "승인되지 않은 카테고리 또는 태그 코드입니다."),
STORE_STATE_CONFLICT(HttpStatus.CONFLICT, "STORE_005", "현재 매장 상태에서 요청한 작업을 수행할 수 없습니다."),
SCHEDULE_CONFLICT(HttpStatus.CONFLICT, "STORE_006", "영업 또는 예약 접수 시간대가 충돌합니다."),
VERIFICATION_STATE_CONFLICT(HttpStatus.CONFLICT, "STORE_007", "현재 입점 검증 상태에서 운영할 수 없습니다."),
PICKUP_NOT_ELIGIBLE(HttpStatus.CONFLICT, "STORE_008", "픽업 자격이 없어 픽업 기능을 활성화할 수 없습니다."),
MENU_NOT_FOUND(HttpStatus.NOT_FOUND, "STORE_009", "메뉴를 찾을 수 없습니다."),
MENU_STATE_CONFLICT(HttpStatus.CONFLICT, "STORE_010", "현재 메뉴 상태에서 요청한 전이를 수행할 수 없습니다.");
```

Enums:

```java
public enum BusinessType { CAFE, BAKERY, OTHER }
public enum OperationStatus { OPEN, TEMPORARILY_CLOSED, CLOSED }
public enum PickupEligibility { ELIGIBLE, INELIGIBLE }
public enum Region { SEOUL, BUSAN, DAEGU, DAEJEON, GWANGJU }
public enum VerificationStatus { APPROVED }
```

- [ ] **Step 4: Implement `Store` invariants**

Use scalar `storeOperatorAccountId` instead of a cross-domain JPA association. Map tag codes with `@ElementCollection`, `@CollectionTable(name = "store_tag_assignment")`, and `@Column(name = "tag_code")`.

Required signatures:

```java
public static Store create(
        long storeOperatorAccountId,
        String businessRegistrationNumber,
        BusinessType businessType,
        String name,
        String description,
        Region region,
        String address,
        String storeCategoryCode,
        Set<String> tagCodes,
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled)

public void update(
        String name,
        String description,
        Region region,
        String address,
        String storeCategoryCode,
        Set<String> tagCodes,
        Boolean reservationEnabled,
        Boolean menuHoldEnabled,
        Boolean pickupEnabled,
        OperationStatus operationStatus)

public void requireManagedBy(long operatorAccountId)
```

`create` assigns `APPROVED`, `OPEN`, and `ELIGIBLE` only for `CAFE`/`BAKERY`. Both `create` and `update` call one private pickup invariant method before changing state.

- [ ] **Step 5: Run aggregate tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.entity.StoreTest"
```

Expected: all `StoreTest` tests pass.

- [ ] **Step 6: Commit the aggregate**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/error backend/src/main/java/com/miriyum/domain/store/core/enums backend/src/main/java/com/miriyum/domain/store/core/entity backend/src/test/java/com/miriyum/domain/store/core/entity
git commit -m "feat(store): add store aggregate invariants"
```

---

### Task 2: MySQL Schema and Repository Constraints

**Files:**
- Create: `backend/src/main/resources/db/migration/V8__create_stores.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/repository/StoreRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/repository/StoreRepositoryIT.java`

**Interfaces:**
- Consumes: `Store`, V2 catalog tables, V6 `store_operator_accounts`
- Produces: `findById`, `saveAndFlush`, and MySQL-enforced FK/unique constraints

- [ ] **Step 1: Write the failing Testcontainers repository test**

```java
@SpringBootTest(properties = {
        "miriyum.jwt.secret=test-only-secret-key-must-be-at-least-32-bytes",
        "miriyum.jwt.issuer=miriyum"
})
@Testcontainers(disabledWithoutDocker = true)
@Transactional
class StoreRepositoryIT {

    @Container
    @ServiceConnection
    static MySQLContainer<?> mysql =
            new MySQLContainer<>(DockerImageName.parse("mysql:8.0.40"));

    @Autowired JdbcTemplate jdbcTemplate;

    @Test
    void duplicateActiveBusinessNumberIsRejected() {
        long firstOperator = insertOperator("first@example.com");
        long secondOperator = insertOperator("second@example.com");
        insertStore(firstOperator, "1234567890", "OPEN");

        assertThatThrownBy(() -> insertStore(secondOperator, "1234567890", "OPEN"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void closedStoreDoesNotCascadeDeleteWhenOperatorEnds() {
        long operatorId = insertOperator("owner@example.com");
        long storeId = insertStore(operatorId, "1234567890", "CLOSED");

        assertThatThrownBy(() -> jdbcTemplate.update(
                "DELETE FROM store_operator_accounts WHERE store_operator_account_id = ?", operatorId))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM stores WHERE store_id = ?", Integer.class, storeId)).isOne();
    }

    private long insertOperator(String email) {
        jdbcTemplate.update("""
                INSERT INTO store_operator_accounts
                    (email, password_hash, display_name, status, created_at, updated_at)
                VALUES (?, 'hashed', '운영자', 'ACTIVE', CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, email);
        return jdbcTemplate.queryForObject("""
                SELECT store_operator_account_id
                FROM store_operator_accounts
                WHERE email = ?
                """, Long.class, email);
    }

    private long insertStore(long operatorId, String businessNumber, String operationStatus) {
        jdbcTemplate.update("""
                INSERT INTO stores (
                    store_operator_account_id, business_registration_number, business_type,
                    name, description, region, address, store_category_code,
                    verification_status, operation_status, pickup_eligibility,
                    reservation_enabled, menu_hold_enabled, pickup_enabled,
                    created_at, updated_at)
                VALUES (?, ?, 'CAFE', '미리윰', '', 'SEOUL', '서울시 중구',
                        'CAFE_BAKERY', 'APPROVED', ?, 'ELIGIBLE',
                        TRUE, TRUE, TRUE, CURRENT_TIMESTAMP(6), CURRENT_TIMESTAMP(6))
                """, operatorId, businessNumber, operationStatus);
        return jdbcTemplate.queryForObject("""
                SELECT store_id FROM stores
                WHERE store_operator_account_id = ? AND business_registration_number = ?
                """, Long.class, operatorId, businessNumber);
    }
}
```

- [ ] **Step 2: Run the repository test and verify failure**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.repository.StoreRepositoryIT"
```

Expected: Flyway/JPA failure because V8 and `StoreRepository` do not exist.

- [ ] **Step 3: Create V8 migration**

Create `stores` with:

```sql
CREATE TABLE stores (
    store_id BIGINT NOT NULL AUTO_INCREMENT,
    store_operator_account_id BIGINT NOT NULL,
    business_registration_number CHAR(10) NOT NULL,
    business_type VARCHAR(20) NOT NULL,
    name VARCHAR(100) NOT NULL,
    description VARCHAR(1000) NOT NULL,
    region VARCHAR(20) NOT NULL,
    address VARCHAR(300) NOT NULL,
    store_category_code VARCHAR(50) COLLATE utf8mb4_0900_as_cs NOT NULL,
    verification_status VARCHAR(20) NOT NULL,
    operation_status VARCHAR(30) NOT NULL,
    pickup_eligibility VARCHAR(20) NOT NULL,
    reservation_enabled BOOLEAN NOT NULL,
    menu_hold_enabled BOOLEAN NOT NULL,
    pickup_enabled BOOLEAN NOT NULL,
    active_business_registration_number CHAR(10)
        GENERATED ALWAYS AS (
            CASE WHEN operation_status <> 'CLOSED'
                 THEN business_registration_number ELSE NULL END
        ) STORED,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    PRIMARY KEY (store_id),
    CONSTRAINT uk_stores_active_business_number
        UNIQUE (active_business_registration_number),
    CONSTRAINT fk_stores_operator
        FOREIGN KEY (store_operator_account_id)
        REFERENCES store_operator_accounts (store_operator_account_id)
        ON DELETE RESTRICT,
    CONSTRAINT fk_stores_category
        FOREIGN KEY (store_category_code)
        REFERENCES store_category (code)
        ON DELETE RESTRICT,
    CONSTRAINT ck_stores_business_number
        CHECK (REGEXP_LIKE(business_registration_number, '^[0-9]{10}$', 'c')),
    CONSTRAINT ck_stores_pickup_eligibility
        CHECK (
            (business_type IN ('CAFE', 'BAKERY') AND pickup_eligibility = 'ELIGIBLE')
            OR (business_type = 'OTHER'
                AND pickup_eligibility = 'INELIGIBLE'
                AND pickup_enabled = FALSE)
        )
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;

CREATE TABLE store_tag_assignment (
    store_id BIGINT NOT NULL,
    tag_code VARCHAR(50) COLLATE utf8mb4_0900_as_cs NOT NULL,
    PRIMARY KEY (store_id, tag_code),
    CONSTRAINT fk_store_tag_assignment_store
        FOREIGN KEY (store_id) REFERENCES stores (store_id) ON DELETE CASCADE,
    CONSTRAINT fk_store_tag_assignment_tag
        FOREIGN KEY (tag_code) REFERENCES store_tag (code) ON DELETE RESTRICT
) ENGINE = InnoDB DEFAULT CHARSET = utf8mb4 COLLATE = utf8mb4_0900_ai_ci;
```

- [ ] **Step 4: Add repository**

```java
public interface StoreRepository extends JpaRepository<Store, Long> {
}
```

- [ ] **Step 5: Run MySQL repository tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.repository.StoreRepositoryIT"
```

Expected with Docker: all tests pass with zero skipped. Without Docker: Testcontainers class is skipped and must later be rerun in Docker before completion.

- [ ] **Step 6: Commit persistence**

```powershell
git add backend/src/main/resources/db/migration/V8__create_stores.sql backend/src/main/java/com/miriyum/domain/store/core/repository backend/src/test/java/com/miriyum/domain/store/core/repository
git commit -m "feat(store): persist stores with active business uniqueness"
```

---

### Task 3: Request Contracts, Catalog Validation, and Fingerprints

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreModesRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreCreateRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreUpdateRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/dto/ManagedStoreResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreCatalogPolicy.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandFingerprint.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreCatalogPolicyTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreCommandFingerprintTest.java`

**Interfaces:**
- Consumes: `CatalogService.isActiveCode`, `CatalogService.findUnknownCodes`, `CatalogKind`
- Produces: validated request records, `ManagedStoreResponse.from(Store)`, stable SHA-256 fingerprints

- [ ] **Step 1: Write failing policy and fingerprint tests**

```java
@ExtendWith(MockitoExtension.class)
class StoreCatalogPolicyTest {
    @Mock CatalogService catalogService;
    @InjectMocks StoreCatalogPolicy policy;

    @Test
    void rejectsInactiveCategory() {
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "UNKNOWN"))
                .willReturn(false);

        assertThatThrownBy(() -> policy.validate("UNKNOWN", List.of()))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.CATALOG_CODE_INVALID);
    }

    @Test
    void rejectsDuplicateTagsBeforeCatalogLookup() {
        assertThatThrownBy(() -> policy.validate("KOREAN", List.of("DATE", "DATE")))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }
}
```

```java
class StoreCommandFingerprintTest {
    @Test
    void tagOrderDoesNotChangeCreateFingerprint() {
        StoreCreateRequest first = requestWithTags(List.of("DATE", "QUIET"));
        StoreCreateRequest second = requestWithTags(List.of("QUIET", "DATE"));

        assertThat(StoreCommandFingerprint.forCreate(first))
                .isEqualTo(StoreCommandFingerprint.forCreate(second));
    }

    @Test
    void targetStoreIdChangesUpdateFingerprint() {
        StoreUpdateRequest request = updateName("새 이름");

        assertThat(StoreCommandFingerprint.forUpdate(1L, request))
                .isNotEqualTo(StoreCommandFingerprint.forUpdate(2L, request));
    }

    private StoreCreateRequest requestWithTags(List<String> tags) {
        return new StoreCreateRequest(
                "1234567890", BusinessType.CAFE, "미리윰", "",
                Region.SEOUL, "서울시 중구", "CAFE_BAKERY", tags,
                new StoreModesRequest(true, true, true));
    }

    private StoreUpdateRequest updateName(String name) {
        return new StoreUpdateRequest(
                name, null, null, null, null, null, null, null);
    }
}
```

- [ ] **Step 2: Run tests and verify failure**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreCatalogPolicyTest" --tests "com.miriyum.domain.store.core.service.StoreCommandFingerprintTest"
```

Expected: compilation fails because DTOs, policy, and fingerprint class do not exist.

- [ ] **Step 3: Implement request and response records**

Use these exact field types:

```java
public record StoreModesRequest(
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled) {}

public record StoreCreateRequest(
        @Pattern(regexp = "^[0-9]{10}$") String businessRegistrationNumber,
        @NotNull BusinessType businessType,
        @NotBlank @Size(max = 100) String name,
        @NotNull @Size(max = 1000) String description,
        @NotNull Region region,
        @NotBlank @Size(max = 300) String address,
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String storeCategoryCode,
        @NotNull @Size(max = 20) List<
                @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String> tagCodes,
        @NotNull @Valid StoreModesRequest modes) {}

public record StoreUpdateRequest(
        @Size(min = 1, max = 100) String name,
        @Size(max = 1000) String description,
        Region region,
        @Size(min = 1, max = 300) String address,
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String storeCategoryCode,
        @Size(max = 20) List<
                @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String> tagCodes,
        @Valid StoreModesRequest modes,
        OperationStatus operationStatus) {
    @AssertTrue(message = "수정할 필드가 하나 이상 필요합니다.")
    public boolean isAnyFieldPresent() {
        return name != null
                || description != null
                || region != null
                || address != null
                || storeCategoryCode != null
                || tagCodes != null
                || modes != null
                || operationStatus != null;
    }
}
```

`ManagedStoreResponse` fields must be:

```java
Long storeId,
String name,
Region region,
String address,
String storeCategoryCode,
VerificationStatus verificationStatus,
OperationStatus operationStatus,
PickupEligibility pickupEligibility,
StoreModesRequest modes
```

- [ ] **Step 4: Implement catalog policy**

```java
public void validate(String categoryCode, List<String> tagCodes) {
    if (tagCodes.size() != new HashSet<>(tagCodes).size()) {
        throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }
    if (!catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, categoryCode)
            || !catalogService.findUnknownCodes(CatalogKind.STORE_TAG, tagCodes).isEmpty()) {
        throw new ServiceException(StoreErrorCode.CATALOG_CODE_INVALID);
    }
}
```

- [ ] **Step 5: Implement canonical fingerprints**

`forCreate` uses method, normalized route, all scalar values, mode booleans, and sorted tag codes. `forUpdate` additionally includes `storeId` and explicit `<absent>` markers for nullable fields. Both call `RequestFingerprint.of(canonicalInput)`.

```java
public static String forCreate(StoreCreateRequest request)
public static String forUpdate(long storeId, StoreUpdateRequest request)
```

- [ ] **Step 6: Run contract tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreCatalogPolicyTest" --tests "com.miriyum.domain.store.core.service.StoreCommandFingerprintTest"
```

Expected: all focused tests pass.

- [ ] **Step 7: Commit contracts**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/core/dto backend/src/main/java/com/miriyum/domain/store/core/service/StoreCatalogPolicy.java backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandFingerprint.java backend/src/test/java/com/miriyum/domain/store/core/service
git commit -m "feat(store): validate store write contracts"
```

---

### Task 4: Transactional Store Service and Central Authority

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreManagementView.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`

**Interfaces:**
- Consumes: `StoreOperatorAccountService.getMe`, `StoreRepository`, `StoreCatalogPolicy`, `IdempotencyExecutor`, `IdempotencyKey`, `RequestFingerprint`
- Produces: `create`, `getManagedStore`, `update`, `requireManagementAuthority`

- [ ] **Step 1: Write failing service tests**

```java
@ExtendWith(MockitoExtension.class)
class StoreServiceTest {
    @Mock StoreOperatorAccountService operatorAccountService;
    @Mock StoreRepository storeRepository;
    @Mock StoreCatalogPolicy catalogPolicy;
    @Mock IdempotencyExecutor idempotencyExecutor;
    @Spy ObjectMapper objectMapper = new ObjectMapper();
    @InjectMocks StoreService storeService;

    @Test
    void createChecksActiveAccountAndCatalogBeforePersistence() {
        StoreCreateRequest request = validCreateRequest();
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<ManagedStoreResponse>> work = invocation.getArgument(1);
            BusinessResult<ManagedStoreResponse> result = work.get();
            return outcome(201, result.data());
        });
        given(storeRepository.saveAndFlush(any(Store.class)))
                .willAnswer(invocation -> invocation.getArgument(0));

        storeService.create(11L, IdempotencyKey.parse(TEST_KEY), request);

        InOrder order = inOrder(operatorAccountService, catalogPolicy, storeRepository);
        order.verify(operatorAccountService).getMe(11L);
        order.verify(catalogPolicy).validate("CAFE_BAKERY", List.of("DATE"));
        order.verify(storeRepository).saveAndFlush(any(Store.class));
    }

    @Test
    void getExistingStoreOwnedByAnotherOperatorReturns403() {
        Store store = storeOwnedBy(11L);
        given(storeRepository.findById(7L)).willReturn(Optional.of(store));

        assertThatThrownBy(() -> storeService.getManagedStore(12L, 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.ACCESS_DENIED);
    }

    @Test
    void missingStoreReturns404() {
        given(storeRepository.findById(7L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> storeService.getManagedStore(11L, 7L))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);
    }

    private StoreCreateRequest validCreateRequest() {
        return new StoreCreateRequest(
                "1234567890", BusinessType.CAFE, "미리윰", "",
                Region.SEOUL, "서울시 중구", "CAFE_BAKERY", List.of("DATE"),
                new StoreModesRequest(true, true, true));
    }

    private Store storeOwnedBy(long operatorId) {
        return Store.create(
                operatorId, "1234567890", BusinessType.CAFE, "미리윰",
                "", Region.SEOUL, "서울시 중구", "CAFE_BAKERY",
                Set.of("DATE"), true, true, true);
    }

    private IdempotentOutcome outcome(int status, ManagedStoreResponse response) {
        return new IdempotentOutcome(
                false, status, "SUCCESS", "STORE", "7",
                objectMapper.valueToTree(response));
    }
}
```

- [ ] **Step 2: Run service tests and verify failure**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreServiceTest"
```

Expected: compilation fails because service/result/view types do not exist.

- [ ] **Step 3: Implement command and authority result records**

```java
public record StoreCommandResult(int httpStatus, ManagedStoreResponse data) {}

public record StoreManagementView(
        long storeId,
        OperationStatus operationStatus,
        VerificationStatus verificationStatus,
        PickupEligibility pickupEligibility) {}
```

- [ ] **Step 4: Implement `StoreService` public API**

Required signatures:

```java
@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
public StoreCommandResult create(
        long operatorAccountId, IdempotencyKey idempotencyKey, StoreCreateRequest request)

@Transactional(readOnly = true)
public ManagedStoreResponse getManagedStore(long operatorAccountId, long storeId)

@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
public StoreCommandResult update(
        long operatorAccountId, long storeId,
        IdempotencyKey idempotencyKey, StoreUpdateRequest request)

@Transactional(readOnly = true)
public StoreManagementView requireManagementAuthority(long operatorAccountId, long storeId)
```

Before every operation call `operatorAccountService.getMe(operatorAccountId)` so suspended or missing accounts are rejected by the auth contract. `loadStore` throws `STORE_001`; `requireManagedBy` then distinguishes an existing but unauthorized store as `STORE_003`.

Create command:

```java
new IdempotencyCommand(
        "store-operator",
        operatorAccountId,
        "STORE_REGISTER",
        idempotencyKey.value(),
        StoreCommandFingerprint.forCreate(request))
```

Update command:

```java
new IdempotencyCommand(
        "store-operator",
        operatorAccountId,
        "STORE_UPDATE",
        idempotencyKey.value(),
        StoreCommandFingerprint.forUpdate(storeId, request))
```

Map `DataIntegrityViolationException` whose constraint contains `uk_stores_active_business_number` to `STORE_002`; rethrow unrelated integrity errors. Convert `IdempotentOutcome.data()` back to `ManagedStoreResponse` with the injected `ObjectMapper`.

- [ ] **Step 5: Run service tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreServiceTest"
```

Expected: all focused tests pass.

- [ ] **Step 6: Commit service**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/core/service backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java
git commit -m "feat(store): add idempotent store management service"
```

---

### Task 5: Store-Operator Security and HTTP API

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/core/config/StoreManagementSecurityConfig.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/core/controller/StoreController.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java`

**Interfaces:**
- Consumes: `JwtAuthenticationFilter`, `TokenNamespace.STORE_OPERATOR`, `AuthenticatedPrincipal`, `StoreService`
- Produces: POST/GET/PATCH `/api/v1/store-operator/stores`

- [ ] **Step 1: Write failing MockMvc tests**

```java
@WebMvcTest(StoreController.class)
@Import({StoreManagementSecurityConfig.class, GlobalExceptionHandler.class})
class StoreControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean StoreService storeService;
    @MockBean JwtTokenProvider jwtTokenProvider;

    @Test
    void missingBearerTokenReturns401() throws Exception {
        mockMvc.perform(get("/api/v1/store-operator/stores/7"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_004"));
    }

    @Test
    void createReturns201Envelope() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.create(eq(11L), any(IdempotencyKey.class), any()))
                .willReturn(new StoreCommandResult(201, managedStore(7L)));

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header("Authorization", "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value("SUCCESS"))
                .andExpect(jsonPath("$.data.storeId").value(7));
    }

    @Test
    void invalidIdempotencyKeyReturnsCommon004() throws Exception {
        authenticateStoreOperator(11L);

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header("Authorization", "Bearer store-token")
                        .header("Idempotency-Key", "bad-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_004"));
    }

    @Test
    void otherOperatorReturnsStore003() throws Exception {
        authenticateStoreOperator(12L);
        given(storeService.getManagedStore(12L, 7L))
                .willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED));

        mockMvc.perform(get("/api/v1/store-operator/stores/7")
                        .header("Authorization", "Bearer store-token"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_003"));
    }

    @Test
    void missingStoreReturnsStore001() throws Exception {
        authenticateStoreOperator(11L);
        given(storeService.getManagedStore(11L, 7L))
                .willThrow(new ServiceException(StoreErrorCode.STORE_NOT_FOUND));

        mockMvc.perform(get("/api/v1/store-operator/stores/7")
                        .header("Authorization", "Bearer store-token"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("STORE_001"));
    }

    @Test
    void duplicateTagsReturnCommon001() throws Exception {
        authenticateStoreOperator(11L);
        willThrow(new ServiceException(CommonErrorCode.VALIDATION_FAILED))
                .given(storeService).create(eq(11L), any(), any());

        mockMvc.perform(post("/api/v1/store-operator/stores")
                        .header("Authorization", "Bearer store-token")
                        .header("Idempotency-Key", TEST_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(validCreateJson().replace(
                                "\"tagCodes\":[\"DATE\"]",
                                "\"tagCodes\":[\"DATE\",\"DATE\"]")))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("COMMON_001"));
    }

    private void authenticateStoreOperator(long accountId) {
        given(jwtTokenProvider.parseAccessToken("store-token"))
                .willReturn(new ParsedToken(TokenNamespace.STORE_OPERATOR, accountId));
    }

    private String validCreateJson() {
        return """
                {
                  "businessRegistrationNumber": "1234567890",
                  "businessType": "CAFE",
                  "name": "미리윰",
                  "description": "",
                  "region": "SEOUL",
                  "address": "서울시 중구",
                  "storeCategoryCode": "CAFE_BAKERY",
                  "tagCodes": ["DATE"],
                  "modes": {
                    "reservationEnabled": true,
                    "menuHoldEnabled": true,
                    "pickupEnabled": true
                  }
                }
                """;
    }

    private ManagedStoreResponse managedStore(long storeId) {
        return new ManagedStoreResponse(
                storeId, "미리윰", Region.SEOUL, "서울시 중구", "CAFE_BAKERY",
                VerificationStatus.APPROVED, OperationStatus.OPEN,
                PickupEligibility.ELIGIBLE,
                new StoreModesRequest(true, true, true));
    }
}
```

- [ ] **Step 2: Run controller tests and verify failure**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.controller.StoreControllerTest"
```

Expected: compilation fails because controller and security configuration do not exist.

- [ ] **Step 3: Implement store management filter chain**

```java
@Bean
@Order(0)
SecurityFilterChain storeManagementFilterChain(
        HttpSecurity http,
        JwtTokenProvider jwtTokenProvider,
        ObjectMapper objectMapper) throws Exception {
    http.securityMatcher("/api/v1/store-operator/stores/**")
            .csrf(AbstractHttpConfigurer::disable)
            .sessionManagement(session ->
                    session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
            .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
            .exceptionHandling(exception -> exception
                    .authenticationEntryPoint(new JwtAuthenticationEntryPoint(objectMapper))
                    .accessDeniedHandler(new JwtAccessDeniedHandler(objectMapper)))
            .addFilterBefore(
                    new JwtAuthenticationFilter(jwtTokenProvider, TokenNamespace.STORE_OPERATOR),
                    UsernamePasswordAuthenticationFilter.class);
    return http.build();
}
```

- [ ] **Step 4: Implement controller endpoints**

```java
@PostMapping
public ResponseEntity<ApiResponse<ManagedStoreResponse>> create(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
        @Valid @RequestBody StoreCreateRequest request)

@GetMapping("/{storeId}")
public ApiResponse<ManagedStoreResponse> get(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable long storeId)

@PatchMapping("/{storeId}")
public ResponseEntity<ApiResponse<ManagedStoreResponse>> update(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable long storeId,
        @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
        @Valid @RequestBody StoreUpdateRequest request)
```

POST returns the service’s stored HTTP status (201); PATCH returns 200; GET returns 200. Parse headers only with `IdempotencyKey.parse(rawKey)`.

- [ ] **Step 5: Run controller tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.controller.StoreControllerTest"
```

Expected: authentication, envelope, validation, 403, 404, and idempotency header tests pass.

- [ ] **Step 6: Commit HTTP boundary**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/core/config backend/src/main/java/com/miriyum/domain/store/core/controller backend/src/test/java/com/miriyum/domain/store/core/controller
git commit -m "feat(store): expose secured store management API"
```

---

### Task 6: Atomicity, Concurrency, and Full Verification

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/repository/StoreRepositoryIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java`
- Verify: all #33 files and Issue allowlist

**Interfaces:**
- Consumes: complete #33 implementation
- Produces: executable evidence for Flyway, concurrency, rollback, HTTP contract, and regression safety

- [ ] **Step 1: Add concurrent registration integration test**

Use two independent `TransactionTemplate` executions and a `CountDownLatch` start gate. Both attempt the same business number under different operators. Assert exactly one success, one `DataIntegrityViolationException`, and one active row:

```java
assertThat(List.of(firstResult, secondResult))
        .filteredOn(RegistrationResult::success)
        .hasSize(1);
assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM stores WHERE active_business_registration_number = ?",
        Integer.class, "1234567890")).isOne();
```

- [ ] **Step 2: Add rollback integration test**

Execute `IdempotencyExecutor` and store insertion in one transaction, then force an exception after `saveAndFlush`. Assert both tables remain empty for the business key:

```java
assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM stores WHERE business_registration_number = ?",
        Integer.class, "1234567890")).isZero();
assertThat(jdbcTemplate.queryForObject(
        "SELECT COUNT(*) FROM idempotency_commands WHERE command_type = 'STORE_REGISTER'",
        Integer.class)).isZero();
```

- [ ] **Step 3: Run all #33 focused tests with Docker**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.*"
```

Expected: all tests pass and Testcontainers tests report zero skipped.

- [ ] **Step 4: Run complete backend verification**

```powershell
.\gradlew.bat clean build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 5: Check diff hygiene and Issue scope**

From repository root:

```powershell
git diff --check origin/dev...HEAD
git diff --name-status origin/dev...HEAD
git log --oneline origin/dev..HEAD
```

Expected:

- no whitespace errors;
- no frontend, schedule, menu, search, or identity-stub setting changes;
- after PR #60 is merged and the branch is rebased, no auth dependency commit or V6 file appears in the #33 diff;
- only #33 implementation, tests, V8 migration, and its design/plan evidence remain.

- [ ] **Step 6: Commit verification additions**

```powershell
git add backend/src/test/java/com/miriyum/domain/store/core
git commit -m "test(store): verify store registration atomicity"
```

- [ ] **Step 7: Record final evidence before publishing**

Capture these exact facts for the PR body:

```text
Focused test command and passed test count
Testcontainers MySQL image and skipped count
Full clean build result
git diff --check result
PR #60 merge/rebase status
Issue #33 file allowlist comparison
```

Do not claim #34, #35, #36, or frontend completion in the #33 PR.
