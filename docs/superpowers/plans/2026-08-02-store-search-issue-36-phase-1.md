# Store Search Issue #36 Phase 1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 예약 도메인 계약 없이도 검증 가능한 공개 매장 검색 조건, MySQL 후보 조회, `NOT_REQUESTED` 응답 투영을 구현한다.

**Architecture:** `com.miriyum.domain.store.search` 안에 조건 모델, catalog 검증 service, JDBC 기반 읽기 repository와 공개 요약 DTO를 둔다. 검색 후보는 Store/Menu 정본 테이블에서 한 번의 page query와 count query로 읽으며, 예약 조건이 없는 경로만 `NOT_REQUESTED`로 투영한다. 공개 controller, Store 일정 hydrate, Reservation batch 호출과 `availableOnly=true` 결과 페이지는 Phase 2까지 만들지 않는다.

**Tech Stack:** Java 21, Spring Boot, Spring JDBC `NamedParameterJdbcTemplate`, Spring Data `Page`, JUnit 5, AssertJ, Mockito, Testcontainers MySQL 8.0.40, Flyway.

## Global Constraints

- 수정 범위는 Issue #36에 정렬된 `backend/src/main/java/com/miriyum/domain/store/search/**`, 대응 테스트, `backend/src/main/resources/db/migration/V17__add_store_search_indexes.sql`, 이 설계·계획 문서로 제한한다.
- Frontend, 공개 controller, Reservation Entity·Repository, MenuHold Entity·Repository, QueryDSL, 검색 엔진, 지도·거리, 추천, 이미지 코드는 추가하지 않는다.
- 다른 도메인은 최신 `dev`에 병합된 공개 Service·DTO만 사용할 수 있다. Phase 1은 Reservation 타입을 import하지 않는다.
- production fake, 임시 종료 시각, 임의 `includesInfants=false`, 예약 수용량 복제 계산을 만들지 않는다.
- 공개 후보는 `APPROVED`이고 `CLOSED`가 아닌 매장, `retired=false`, `VISIBLE`, 현재 `PUBLISHED` 버전인 메뉴만 사용한다.
- `SOLD_OUT`과 `PAUSED` 메뉴는 공개 상태에서 제외하지 않는다.
- keyword는 Unicode 공백을 하나로 정규화하고 `%`, `_`, escape 문자를 SQL wildcard가 아닌 리터럴로 검색한다.
- 정렬은 `name,asc`, `name,desc`, `createdAt,desc`, `createdAt,asc`만 허용하고 항상 `store_id` 오름차순 tie-breaker를 붙인다.
- 공개 ID는 `String`으로 투영한다.
- 각 구현 task는 RED → GREEN → 관련 회귀 테스트 → 커밋 순서를 지킨다.

---

## File Structure

- `search/model/StoreSearchQuery.java`: 원시 query parameter 정규화, 예약 조건 all-or-none, page/size 검증.
- `search/model/StoreSearchSort.java`: 외부 sort allowlist와 고정 SQL order clause 매핑.
- `search/model/ReservationSearchCondition.java`: Phase 2가 소비할 완전한 날짜·시각·인원 값.
- `search/repository/StoreSearchCandidate.java`: DB 후보 projection.
- `search/repository/StoreSearchRepository.java`: 고정 SQL과 bind parameter로 page/count 조회.
- `search/service/StoreSearchCatalogPolicy.java`: `CatalogService` 중립 결과를 `STORE_004`로 매핑.
- `search/service/StoreSearchCoreService.java`: catalog 검증, 후보 조회, 예약 없는 결과 투영.
- `search/dto/PublicStoreSummary.java`: OpenAPI의 목록 매장 필드와 공개 ID.
- `search/dto/PublicStoreModes.java`: 예약·홀드·픽업 mode.
- `search/dto/ReservationAvailability.java`: `NOT_REQUESTED`, `AVAILABLE`, `UNAVAILABLE` 응답 어휘. Phase 1은 첫 값만 생성.
- `V17__add_store_search_indexes.sql`: 공개 매장 및 현재 게시 메뉴 후보용 복합 인덱스.

---

### Task 1: Issue 계약과 Phase 1 허용 경로 정렬

**Files:**
- Modify: GitHub Issue `#36` body only
- Verify: `docs/specs/store-search/spec.md`
- Verify: `docs/specs/store-search/openapi.yaml`

**Interfaces:**
- Consumes: 승인된 2단계 설계 `docs/superpowers/specs/2026-08-02-store-search-issue-36-design.md`
- Produces: 제품 코드가 따를 정확한 package allowlist와 `blocked by #48 / PR #79` 기록

- [x] **Step 1: Issue #36의 현재 본문을 다시 읽는다**

Run through the GitHub connector: fetch Issue #36 and confirm its state is `open`.

- [x] **Step 2: 허용 경로와 의존성을 정확히 갱신한다**

Apply these exact changes without replacing unrelated acceptance criteria:

```text
backend/src/main/java/com/miriyum/store/search/**
→ backend/src/main/java/com/miriyum/domain/store/search/**

backend/src/test/java/com/miriyum/store/search/**
→ backend/src/test/java/com/miriyum/domain/store/search/**

blocked by: 3번 예약 도메인의 batch 가용성 공개 Service·DTO 구현 Issue/PR — 번호 연결 전 구현 금지
→ blocked by: #48, PR #79 및 매장별 종료 시각을 소비할 수 있는 Reservation batch 공개 계약의 dev 병합
```

Append the following phase boundary:

```markdown
### 구현 단계

- Phase 1: 예약 비의존 검색 조건·후보 query·NOT_REQUESTED 투영과 MySQL 테스트
- Phase 2: Store 활성 일정 조회와 Reservation 매장별 batch 계약이 dev에 병합된 뒤 세 공개 API 통합
- Phase 1에서는 불완전한 공개 controller나 production fake를 만들지 않는다.
```

- [x] **Step 3: 갱신 결과를 다시 읽어 범위를 확인한다**

Expected: original endpoints, acceptance criteria and verification list remain present; only the path, concrete dependency and phase boundary change.

---

### Task 2: 검색 조건과 고정 정렬 모델

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/model/ReservationSearchCondition.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/model/StoreSearchSort.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/model/StoreSearchQuery.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/model/StoreSearchQueryTest.java`

**Interfaces:**
- Consumes: `Region`, `CommonErrorCode.VALIDATION_FAILED`, `ServiceException`
- Produces: `StoreSearchQuery.from(...)`, `normalizedKeyword()`, `likePattern()`, `reservationCondition()`, `sort()`, `page()`, `size()`

- [x] **Step 1: all-or-none와 keyword escape 실패 테스트를 작성한다**

```java
class StoreSearchQueryTest {

    @Test
    void rejectsPartialReservationCondition() {
        assertThatThrownBy(() -> StoreSearchQuery.from(
                null, null, null,
                LocalDate.of(2026, 8, 3), null, 2,
                false, "name,asc", 0, 20))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    @Test
    void rejectsAvailableOnlyWithoutReservationCondition() {
        assertThatThrownBy(() -> StoreSearchQuery.from(
                null, null, null, null, null, null,
                true, "name,asc", 0, 20))
                .isInstanceOf(ServiceException.class);
    }

    @Test
    void normalizesWhitespaceAndEscapesLikeMetaCharacters() {
        StoreSearchQuery query = StoreSearchQuery.from(
                "  성수\t100%_카페!  ", Region.SEOUL, "CAFE_BAKERY",
                null, null, null, false, "name,asc", 0, 20);

        assertThat(query.normalizedKeyword()).isEqualTo("성수 100%_카페!");
        assertThat(query.likePattern()).isEqualTo("%성수 100!%!_카페!!%");
    }
}
```

- [x] **Step 2: 모델 테스트를 실행해 RED를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.model.StoreSearchQueryTest"
```

Expected: compilation failure because the three model types do not exist.

- [x] **Step 3: 예약 조건과 sort enum을 최소 구현한다**

```java
public record ReservationSearchCondition(
        LocalDate serviceDate,
        LocalTime startTime,
        int partySize
) {
}
```

```java
public enum StoreSearchSort {
    NAME_ASC("name,asc", "s.name ASC, s.store_id ASC"),
    NAME_DESC("name,desc", "s.name DESC, s.store_id ASC"),
    CREATED_AT_DESC("createdAt,desc", "s.created_at DESC, s.store_id ASC"),
    CREATED_AT_ASC("createdAt,asc", "s.created_at ASC, s.store_id ASC");

    public static StoreSearchSort parse(String value) {
        String resolved = value == null || value.isBlank() ? "name,asc" : value;
        return Arrays.stream(values())
                .filter(candidate -> candidate.externalValue.equals(resolved))
                .findFirst()
                .orElseThrow(() -> new ServiceException(CommonErrorCode.VALIDATION_FAILED));
    }
}
```

- [x] **Step 4: `StoreSearchQuery.from`을 구현한다**

Use this exact signature:

```java
public static StoreSearchQuery from(
        String keyword,
        Region region,
        String storeCategoryCode,
        LocalDate serviceDate,
        LocalTime startTime,
        Integer partySize,
        boolean availableOnly,
        String sort,
        int page,
        int size
)
```

Implement these exact invariants:

```java
boolean anyReservationValue = serviceDate != null || startTime != null || partySize != null;
boolean allReservationValues = serviceDate != null && startTime != null && partySize != null;
if (anyReservationValue != allReservationValues
        || (availableOnly && !allReservationValues)
        || (partySize != null && (partySize < 1 || partySize > 100))
        || page < 0 || size < 1 || size > 100) {
    throw new ServiceException(CommonErrorCode.VALIDATION_FAILED);
}
```

Normalize keyword with `strip()` and `replaceAll("\\s+", " ")`; convert blank to `null`; reject normalized values longer than 100. Build the LIKE pattern by escaping `!` as `!!`, `%` as `!%`, and `_` as `!_`, then wrap it in `%`. MySQL uses `ESCAPE '!'`, so a backslash remains a literal backslash.

- [x] **Step 5: boundary tests를 추가하고 GREEN을 확인한다**

Add tests for party sizes `1` and `100`, invalid `0` and `101`, pages `-1`, sizes `0` and `101`, unknown sort, default sort, blank keyword and a literal backslash.

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.model.StoreSearchQueryTest"
```

Expected: PASS.

- [x] **Step 6: Task 2를 커밋한다**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/model backend/src/test/java/com/miriyum/domain/store/search/model
git commit -m "feat(search): validate public store search criteria"
```

---

### Task 3: Catalog code 정책 경계

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/service/StoreSearchCatalogPolicy.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/service/StoreSearchCatalogPolicyTest.java`

**Interfaces:**
- Consumes: `CatalogService.isActiveCode(CatalogKind.STORE_CATEGORY, code)`
- Produces: `void requireActiveStoreCategory(String code)`; `null` means no filter, inactive value throws `STORE_004`

- [x] **Step 1: catalog mapping 실패 테스트를 작성한다**

```java
@ExtendWith(MockitoExtension.class)
class StoreSearchCatalogPolicyTest {

    @Mock CatalogService catalogService;
    @InjectMocks StoreSearchCatalogPolicy policy;

    @Test
    void mapsInactiveCodeToStore004() {
        given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "UNKNOWN"))
                .willReturn(false);

        assertThatThrownBy(() -> policy.requireActiveStoreCategory("UNKNOWN"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.CATALOG_CODE_INVALID));
    }

}
```

- [x] **Step 2: 테스트를 실행해 RED를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.service.StoreSearchCatalogPolicyTest"
```

Expected: compilation failure because `StoreSearchCatalogPolicy` does not exist.

- [x] **Step 3: 정책 service를 구현한다**

```java
@Component
public class StoreSearchCatalogPolicy {
    private final CatalogService catalogService;

    public void requireActiveStoreCategory(String code) {
        if (code == null) {
            return;
        }
        if (!catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, code)) {
            throw new ServiceException(StoreErrorCode.CATALOG_CODE_INVALID);
        }
    }
}
```

- [x] **Step 4: 정책 테스트 GREEN을 확인한다**

Run the Task 3 test class. Expected: PASS.

- [x] **Step 5: Task 3을 커밋한다**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/service/StoreSearchCatalogPolicy.java backend/src/test/java/com/miriyum/domain/store/search/service/StoreSearchCatalogPolicyTest.java
git commit -m "feat(search): validate store category filters"
```

---

### Task 4: MySQL 공개 매장 후보 page query

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/repository/StoreSearchCandidate.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/repository/StoreSearchRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/repository/StoreSearchRepositoryIT.java`

**Interfaces:**
- Consumes: `StoreSearchQuery`, its fixed `StoreSearchSort.orderByClause()` and bindable values
- Produces: `Page<StoreSearchCandidate> search(StoreSearchQuery query)`

- [x] **Step 1: 공개 상태와 keyword 의미의 MySQL 실패 테스트를 작성한다**

Create fixtures through `StoreRepository`, `MenuRepository` and their public aggregate methods:

```java
@Test
void searchesStoreNamePublishedMenuNameAndRegionLabelWithoutDuplicates() {
    Store seoul = createStore("성수 키친", Region.SEOUL, OperationStatus.OPEN);
    publishVisibleMenu(seoul, "파스타 100%_특선", MenuSellingStatus.SOLD_OUT);
    publishVisibleMenu(seoul, "파스타 두번째", MenuSellingStatus.PAUSED);
    createStore("폐점 파스타", Region.SEOUL, OperationStatus.CLOSED);

    Page<StoreSearchCandidate> byMenu = repository.search(query("100%_특선"));
    Page<StoreSearchCandidate> byRegion = repository.search(query("서울"));

    assertThat(byMenu.getContent()).extracting(StoreSearchCandidate::storeId)
            .containsExactly(seoul.getId());
    assertThat(byRegion.getContent()).extracting(StoreSearchCandidate::storeId)
            .containsExactly(seoul.getId());
}
```

Also add separate tests proving `HIDDEN`, retired and non-published menu versions do not match, while `SOLD_OUT` and `PAUSED` published menus do match.

- [x] **Step 2: repository IT를 실행해 RED를 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.repository.StoreSearchRepositoryIT"
```

Expected: compilation failure because repository and candidate projection do not exist.

- [x] **Step 3: 후보 projection을 구현한다**

```java
public record StoreSearchCandidate(
        long storeId,
        String name,
        Region region,
        String address,
        String storeCategoryCode,
        OperationStatus operationStatus,
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled,
        LocalDateTime createdAt
) {
}
```

- [x] **Step 4: 고정 SQL repository를 구현한다**

Use `NamedParameterJdbcTemplate`. The data and count statements must share this exact predicate:

```sql
FROM stores s
WHERE s.verification_status = 'APPROVED'
  AND s.operation_status <> 'CLOSED'
  AND (:region IS NULL OR s.region = :region)
  AND (:categoryCode IS NULL OR s.store_category_code = :categoryCode)
  AND (
      :keywordPattern IS NULL
      OR LOWER(s.name) LIKE :keywordPattern ESCAPE '!'
      OR LOWER(CASE s.region
          WHEN 'SEOUL' THEN '서울'
          WHEN 'BUSAN' THEN '부산'
          WHEN 'DAEGU' THEN '대구'
          WHEN 'DAEJEON' THEN '대전'
          WHEN 'GWANGJU' THEN '광주'
      END) LIKE :keywordPattern ESCAPE '!'
      OR EXISTS (
          SELECT 1
          FROM menus m
          JOIN menu_versions mv
            ON mv.menu_id = m.menu_id
           AND mv.version_number = m.published_version_number
          WHERE m.store_id = s.store_id
            AND m.retired = FALSE
            AND m.visibility = 'VISIBLE'
            AND mv.status = 'PUBLISHED'
            AND LOWER(mv.name) LIKE :keywordPattern ESCAPE '!'
      )
  )
```

The data statement selects the candidate record columns, appends only `query.sort().orderByClause()`, then `LIMIT :limit OFFSET :offset`. Do not concatenate any raw request value. The count statement uses `SELECT COUNT(*)` and the same predicate.

- [x] **Step 5: repository IT GREEN을 확인한다**

Add assertions for all four sorts and duplicate names: the second key must be `store_id ASC`. Run the Task 4 test class. Expected: PASS with MySQL 8.0.40.

- [x] **Step 6: Task 4를 커밋한다**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/repository backend/src/test/java/com/miriyum/domain/store/search/repository
git commit -m "feat(search): query public store candidates"
```

---

### Task 5: `NOT_REQUESTED` 공개 요약 투영 service

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/search/dto/ReservationAvailability.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/dto/PublicStoreModes.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/dto/PublicStoreSummary.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/search/service/StoreSearchCoreService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/search/service/StoreSearchCoreServiceTest.java`

**Interfaces:**
- Consumes: `StoreSearchCatalogPolicy.requireActiveStoreCategory`, `StoreSearchRepository.search`
- Produces: `Page<PublicStoreSummary> searchWithoutAvailability(StoreSearchQuery query)`

- [x] **Step 1: service 조정 실패 테스트를 작성한다**

```java
@Test
void validatesCategoryAndMapsCandidatesToNotRequested() {
    StoreSearchQuery query = queryWithoutReservationAndCategory();
    StoreSearchCandidate candidate = new StoreSearchCandidate(
            9007199254740993L, "매장", Region.SEOUL, "주소", "KOREAN",
            OperationStatus.OPEN, true, false, false,
            LocalDateTime.of(2026, 8, 2, 9, 0));
    given(repository.search(query)).willReturn(new PageImpl<>(List.of(candidate)));

    Page<PublicStoreSummary> result = service.searchWithoutAvailability(query);

    assertThat(result.getContent().getFirst().storeId())
            .isEqualTo("9007199254740993");
    assertThat(result.getContent().getFirst().reservationAvailability())
            .isEqualTo(ReservationAvailability.NOT_REQUESTED);
}

@Test
void refusesReservationConditionUntilPhaseTwoContractExists() {
    assertThatThrownBy(() -> service.searchWithoutAvailability(queryWithReservation()))
            .isInstanceOf(IllegalStateException.class);
}

@Test
void rejectsInactiveCategoryThroughTheRealPolicy() {
    StoreSearchCatalogPolicy realPolicy = new StoreSearchCatalogPolicy(catalogService);
    StoreSearchCoreService realService = new StoreSearchCoreService(realPolicy, repository);
    given(catalogService.isActiveCode(CatalogKind.STORE_CATEGORY, "UNKNOWN"))
            .willReturn(false);

    assertThatThrownBy(() -> realService.searchWithoutAvailability(
            queryWithoutReservation("UNKNOWN")))
            .isInstanceOfSatisfying(ServiceException.class, exception ->
                    assertThat(exception.getErrorCode())
                            .isEqualTo(StoreErrorCode.CATALOG_CODE_INVALID));
}
```

- [x] **Step 2: service 테스트 RED를 확인한다**

Run the Task 5 test class. Expected: compilation failure because DTOs and service do not exist.

- [x] **Step 3: DTO를 구현한다**

```java
public enum ReservationAvailability {
    NOT_REQUESTED,
    AVAILABLE,
    UNAVAILABLE
}
```

```java
public record PublicStoreModes(
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled
) {
}
```

```java
public record PublicStoreSummary(
        String storeId,
        String name,
        Region region,
        String address,
        String storeCategoryCode,
        OperationStatus operationStatus,
        PublicStoreModes modes,
        ReservationAvailability reservationAvailability
) {
}
```

- [x] **Step 4: 읽기 service를 구현한다**

```java
@Service
public class StoreSearchCoreService {
    @Transactional(readOnly = true)
    public Page<PublicStoreSummary> searchWithoutAvailability(StoreSearchQuery query) {
        if (query.reservationCondition() != null) {
            throw new IllegalStateException(
                    "reservation availability contract is not connected");
        }
        catalogPolicy.requireActiveStoreCategory(query.storeCategoryCode());
        return repository.search(query).map(this::toSummary);
    }
}
```

`toSummary` must use `Long.toString(candidate.storeId())`, preserve the candidate fields, create `PublicStoreModes`, and set `ReservationAvailability.NOT_REQUESTED` unconditionally.

- [x] **Step 5: service와 Phase 1 unit 회귀를 실행한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.model.*" --tests "com.miriyum.domain.store.search.service.*"
```

Expected: PASS.

- [x] **Step 6: Task 5를 커밋한다**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/search/dto backend/src/main/java/com/miriyum/domain/store/search/service/StoreSearchCoreService.java backend/src/test/java/com/miriyum/domain/store/search/service/StoreSearchCoreServiceTest.java
git commit -m "feat(search): project stores with not-requested availability"
```

---

### Task 6: 검색 인덱스와 실행계획 검증

**Files:**
- Create: `backend/src/main/resources/db/migration/V17__add_store_search_indexes.sql`
- Modify: `backend/src/test/java/com/miriyum/domain/store/search/repository/StoreSearchRepositoryIT.java`

**Interfaces:**
- Consumes: Task 4 SQL predicate
- Produces: Store 공개 상태·이름 정렬 인덱스와 Menu 현재 게시 버전 lookup 인덱스

- [ ] **Step 1: 인덱스 부재 실패 테스트를 작성한다**

Use `JdbcTemplate` against `information_schema.statistics`:

```java
@Test
void installsPublicSearchIndexesInColumnOrder() {
    assertThat(indexColumns("idx_stores_public_search"))
            .containsExactly(
                    "verification_status", "name", "store_id");
    assertThat(indexColumns("idx_menus_public_search"))
            .containsExactly(
                    "store_id", "retired", "visibility", "published_version_number");
}
```

Add an `EXPLAIN FORMAT=JSON` test for the menu-name predicate and assert the returned JSON contains `idx_menus_public_search`. Seed at least 100 non-matching menus in one batch so the optimizer has a meaningful choice.

- [ ] **Step 2: repository IT를 실행해 RED를 확인한다**

Expected: index assertion fails because V17 does not exist.

- [ ] **Step 3: V17 migration을 작성한다**

```sql
CREATE INDEX idx_stores_public_search
    ON stores (verification_status, name, store_id);

CREATE INDEX idx_menus_public_search
    ON menus (store_id, retired, visibility, published_version_number);
```

Do not edit V8 or V16.

- [ ] **Step 4: Flyway clean-start와 repository IT GREEN을 확인한다**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.repository.StoreSearchRepositoryIT"
```

Expected: PASS; Flyway applies V1 through V17 on MySQL 8.0.40.

- [ ] **Step 5: Task 6을 커밋한다**

```powershell
git add -- backend/src/main/resources/db/migration/V17__add_store_search_indexes.sql backend/src/test/java/com/miriyum/domain/store/search/repository/StoreSearchRepositoryIT.java
git commit -m "perf(search): index public store candidate queries"
```

---

### Task 7: Phase 1 범위·회귀 검증

**Files:**
- Verify: all Phase 1 files
- Modify: `docs/superpowers/plans/2026-08-02-store-search-issue-36-phase-1.md` checkboxes only

**Interfaces:**
- Consumes: Tasks 1–6 committed outputs
- Produces: reviewable Phase 1 branch with no incomplete HTTP API

- [ ] **Step 1: Phase 1 집중 테스트를 실행한다**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.search.*"
```

Expected: PASS, failures 0, errors 0, skipped 0.

- [ ] **Step 2: Store/Menu 기존 회귀 테스트를 실행한다**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.*" --tests "com.miriyum.domain.store.menu.*"
```

Expected: PASS, failures 0, errors 0, skipped 0.

- [ ] **Step 3: 전체 backend 검증을 실행한다**

```powershell
.\gradlew.bat clean build
```

Run with process monitoring rather than a short command timeout. Expected: `BUILD SUCCESSFUL`; record exact test, failure, error and skipped totals from `build/test-results/test/TEST-*.xml`.

- [ ] **Step 4: 범위와 whitespace를 검증한다**

```powershell
git diff --check origin/dev...HEAD
git diff --name-only origin/dev...HEAD
```

Expected changed product paths:

```text
backend/src/main/java/com/miriyum/domain/store/search/**
backend/src/test/java/com/miriyum/domain/store/search/**
backend/src/main/resources/db/migration/V17__add_store_search_indexes.sql
docs/superpowers/specs/2026-08-02-store-search-issue-36-design.md
docs/superpowers/plans/2026-08-02-store-search-issue-36-phase-1.md
```

- [ ] **Step 5: forbidden dependency scan을 실행한다**

```powershell
rg -n "domain\.reservation\.(entity|repository)|domain\.menuhold\.(entity|repository)|QueryDSL|OpenSearch|Meilisearch" backend/src/main/java/com/miriyum/domain/store/search
```

Expected: no matches.

- [ ] **Step 6: 계획 체크박스와 검증 증거를 커밋한다**

```powershell
git add -- docs/superpowers/plans/2026-08-02-store-search-issue-36-phase-1.md
git commit -m "docs(search): record issue 36 phase one verification"
```

## Phase 2 Handoff

Phase 1 완료 후 공개 controller를 추가하지 않는다. 다음 세 조건이 모두 충족되면 별도의 Phase 2 계획을 작성한다.

1. Store가 날짜별 활성 영업·예약 접수 구간과 매장별 종료 시각 계산에 필요한 공개 Service·DTO를 제공한다.
2. Reservation batch가 매장별 조건을 받아 입력 `storeId`와 결과를 안정적으로 대응시키며 최신 `dev`에 병합된다.
3. `includesInfants` 입력 의미와 `availableOnly` pagination/total 전략이 store-search spec과 OpenAPI에서 동일하게 확정된다.
