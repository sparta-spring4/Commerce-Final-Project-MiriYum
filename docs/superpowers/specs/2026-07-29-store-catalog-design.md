# 매장·메뉴 카테고리와 매장 태그 catalog 기반 설계

> 추적 Issue: [#31](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/31)
> 기준 브랜치: `dev` (기준 SHA `1a2e175`, PR #30 병합 상태)
> 기준 계약: PR #27 병합 `docs/specs/store-search/{spec.md,openapi.yaml}`, PR #30 병합 공통 `ApiResponse`·`ErrorResponse`·`ServiceException`
> 정책 원천: `docs/service-policies/03-store-operation.md` (OPER-002~006, 초기 고정 후보)
> seed·버전 승인일: 2026-07-29 (매장 도메인 소유자 @116Lv 승인)
> 설계 상태: 검토 대기

## 목적

2번 담당 영역의 첫 독립 산출물로, 승인된 매장 카테고리·메뉴 카테고리·매장 태그 catalog를 MySQL 정본으로 관리하고 비회원 공개 조회 API를 제공한다. 동시에 후속 매장·메뉴 기능(#33, #35)이 재사용할 **catalog 코드 검증 공개 메서드**를 제공한다.

이 작업은 매장 등록·수정, 메뉴 관리, 공개 검색의 선행 계약이다. 매장·메뉴 Entity, 등록·수정 API, 운영자 인증·인가, 영업시간, 예약·수량 가용성, 검색과 관리자용 catalog 수정 API는 이 산출물의 범위가 아니다.

## 설계 원칙

- catalog code는 **불투명한 문자열**이다. 표시명에서 추측·변환하지 않고, 클라이언트가 표시명을 코드로 보내지 않는다.
- seed 값은 발명하지 않는다. `03-store-operation.md`의 초기 고정 후보에서 최소 MVP 부분집합을 승인받아 사용한다.
- 세 catalog는 동일한 형태(코드·표시명·활성·정렬)를 가지므로 단일 테이블 + 종류 구분으로 관리한다(KISS·DRY).
- catalog 검증 메서드는 "이 종류에 이 코드가 활성으로 존재하는가"만 판정하는 **중립 결과**를 반환한다. `STORE_004` 매핑, 카테고리 개수·중복 규칙은 소비 도메인(#33, #35)이 소유한다.
- 공통 `ApiResponse`·`ErrorResponse`·`ServiceException`을 재사용하고 임시 envelope를 만들지 않는다.
- `SecurityFilterChain`·JWT·인증은 1번 인증 도메인 소유이므로 이 산출물에서 만들지 않는다.
- Flyway migration은 적용 후 수정하지 않는다. 코드 추가·비활성은 항상 새 migration으로 수행하고 기존 코드의 의미를 다른 분류로 재사용하지 않는다.
- DB 동작은 H2가 아니라 Testcontainers MySQL로 검증한다(ADR-002·ADR-004).

## 승인된 catalog seed (v1)

세 catalog 모두 초기 `version = 1`. code는 OpenAPI 패턴 `^[A-Z][A-Z0-9_]{1,49}$`를 준수한다. `sortOrder`는 표시 순서이며 응답 정렬 기준이다. `ETC`가 매장·메뉴 종류에 각각 존재하나 code 유일성은 **종류 안에서**만 요구되므로 충돌이 아니다.

### 매장 카테고리 `STORE_CATEGORY` — 8개

| sortOrder | code | displayName |
|---|---|---|
| 1 | `KOREAN` | 한식 |
| 2 | `CHINESE` | 중식 |
| 3 | `JAPANESE` | 일식 |
| 4 | `WESTERN` | 양식 |
| 5 | `ASIAN` | 아시아 음식 |
| 6 | `CAFE_BAKERY` | 카페·베이커리 |
| 7 | `BAR` | 주점 |
| 8 | `ETC` | 기타 |

정책 후보 중 `분식·간편식`, `퓨전`은 유보한다. 후속 migration으로 새 code를 추가하며 기존 code 의미를 재사용하지 않는다.

### 메뉴 카테고리 `MENU_CATEGORY` — 10개

| sortOrder | code | displayName |
|---|---|---|
| 1 | `RICE` | 밥요리 |
| 2 | `NOODLE` | 면요리 |
| 3 | `SOUP_STEW` | 국·탕·찌개 |
| 4 | `MEAT` | 고기요리 |
| 5 | `SEAFOOD` | 해산물요리 |
| 6 | `PIZZA_BURGER_SANDWICH` | 피자·버거·샌드위치 |
| 7 | `BAKERY` | 베이커리 |
| 8 | `DESSERT` | 디저트 |
| 9 | `BEVERAGE` | 음료 |
| 10 | `ETC` | 기타 |

정책 후보 중 `찜 요리`, `치킨`, `분식·사이드`, `주류`는 유보한다.

### 매장 태그 `STORE_TAG` — 8개

| sortOrder | code | displayName |
|---|---|---|
| 1 | `DATE` | 데이트 |
| 2 | `QUIET` | 조용한 |
| 3 | `GROUP` | 모임 |
| 4 | `SOLO` | 혼밥 |
| 5 | `FAMILY` | 가족식사 |
| 6 | `VEGAN_OPTION` | 비건 옵션 |
| 7 | `ALLERGY_INFO` | 알레르기 안내 제공 |
| 8 | `PET_FRIENDLY` | 반려동물 가능 |

의도적 제외 근거:

- 이용 방식 태그(`예약 가능`·`웨이팅 가능`·`메뉴 홀드 가능`·`포장 가능`·`픽업 가능`)는 매장 `modes`·`pickupEligibility` 구조화 축이 소유한다. spec은 "검색 태그가 modes·픽업 자격을 바꾸지 않는다"고 확정하므로 태그로 중복하면 상태 불일치 위험이 생긴다. `웨이팅 가능`은 고도화 기능이다.
- `대표 메뉴`는 매장 태그가 아니라 메뉴별 `representative` 불리언이다.
- `캐주얼`·`프리미엄`·`매운맛`·`주류 판매`·`노키즈존`·`한정 수량`·`시즌 메뉴`·`점심 특화`·`저녁 특화`는 유보하며 후속 migration으로 추가한다.

## 데이터 모델

동일 형태의 세 catalog를 종류 구분 컬럼으로 단일 테이블에 담는다. 버전은 종류별 1행으로 별도 테이블에 둔다.

### `catalog_item`

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `id` | `BIGINT` | PK, AUTO_INCREMENT | 내부 식별자(응답 비노출) |
| `kind` | `VARCHAR(20)` | NOT NULL | `STORE_CATEGORY`·`MENU_CATEGORY`·`STORE_TAG` |
| `code` | `VARCHAR(50)` | NOT NULL | 불투명 코드, 패턴 `^[A-Z][A-Z0-9_]{1,49}$` |
| `display_name` | `VARCHAR(100)` | NOT NULL | 한글 표시명 |
| `active` | `BOOLEAN` | NOT NULL | 공개·검증 대상 여부 |
| `sort_order` | `INT` | NOT NULL | 종류 안 표시 순서 |

- 유일 제약 `uk_catalog_item_kind_code (kind, code)` — 종류 안에서 code 유일.
- 인덱스 `idx_catalog_item_kind_active_sort (kind, active, sort_order)` — 조회·정렬 지원.
- 문자셋 `utf8mb4`(한글 표시명). 응답에 `id`를 노출하지 않는다.

### `catalog_version`

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `catalog_kind` | `VARCHAR(20)` | PK | catalog 종류 |
| `version` | `BIGINT` | NOT NULL | 현재 활성 seed 버전 |

- 초기 3행: 세 종류 모두 `version = 1`.
- 1차 MVP에서 버전은 seed 거버넌스 표식이다. OpenAPI 공개 응답(`CatalogListData = {items}`)에는 버전 필드가 없으므로 **응답에 노출하지 않는다**. 2차 MVP 검색 캐시 키가 이 버전을 소비할 수 있도록 원천만 마련한다.

## 패키지 구조

```text
com.miriyum.store.catalog
├─ domain
│  ├─ CatalogItem          # JPA 엔티티
│  └─ CatalogKind          # enum: STORE_CATEGORY, MENU_CATEGORY, STORE_TAG
├─ repository
│  └─ CatalogItemRepository
├─ service
│  ├─ CatalogService       # 조회 + 검증 공개 메서드(후속 도메인 재사용점)
│  └─ CatalogItemView      # 조회 결과 내부 표현(code, displayName)
└─ web
   ├─ CatalogController    # 3개 공개 GET
   └─ dto
      ├─ CatalogItemResponse   # {code, displayName}
      └─ CatalogListResponse   # {items}
```

- 매장·메뉴 도메인이 아직 없으므로 `store.catalog` 하위에 독립 배치한다.
- 빈 `config` 패키지나 미사용 추상화를 만들지 않는다.

## 도메인·엔티티 설계

- `CatalogItem`은 `@Entity`, 필드는 위 스키마와 일치한다. `kind`는 `@Enumerated(EnumType.STRING)`.
- 엔티티는 seed 정본을 읽어 제공하는 역할이며 애플리케이션 런타임에서 생성·수정하지 않는다(관리 API는 고도화).
- `CatalogKind` enum 상수는 세 공개 엔드포인트와 1:1 매핑된다.

## Repository

`CatalogItemRepository extends JpaRepository<CatalogItem, Long>`:

- `List<CatalogItem> findByKindAndActiveTrueOrderBySortOrderAsc(CatalogKind kind)` — 목록 조회.
- `List<CatalogItem> findByKindAndActiveTrueAndCodeIn(CatalogKind kind, Collection<String> codes)` — 코드 집합 검증.

네이티브 SQL 문자열 연결을 사용하지 않고 파생 쿼리만 사용한다.

## Service

`CatalogService`는 조회와 검증 공개 메서드를 제공한다. 검증 메서드가 후속 매장·메뉴 도메인의 재사용점이다.

### 조회

- `List<CatalogItemView> getItems(CatalogKind kind)` — 활성 항목을 `sortOrder` 오름차순으로 반환. Controller가 사용.

### 검증(후속 도메인 재사용, 중립 결과)

- `boolean isActiveCode(CatalogKind kind, String code)` — 단일 코드 활성 여부.
- `List<String> findUnknownCodes(CatalogKind kind, Collection<String> codes)` — 입력 코드 중 활성 catalog에 없는 코드 목록. 비어 있으면 전부 유효.

경계 규칙:

- 이 메서드들은 `ServiceException`이나 `STORE_004`를 던지지 않는다. 소비 도메인이 결과를 받아 `STORE_004`로 변환한다.
- "매장 카테고리 정확히 1개", "메뉴 보조 카테고리 0~5개·중복 불가" 같은 개수·중복 규칙은 catalog가 소유하지 않는다. catalog는 코드 멤버십만 판정한다.

## 공개 API 계약

세 GET은 인증 없이 접근하며 활성 항목만 공통 성공 봉투로 반환한다.

| 메서드·경로 | operationId | 응답 data |
|---|---|---|
| `GET /api/v1/store-categories` | `getStoreCategories` | `CatalogListData` |
| `GET /api/v1/menu-categories` | `getMenuCategories` | `CatalogListData` |
| `GET /api/v1/store-tags` | `getStoreTags` | `CatalogListData` |

- 응답 형태: `ApiResponse.success(message, CatalogListResponse)` → `{ "code": "SUCCESS", "message": ..., "data": { "items": [ { "code", "displayName" }, ... ] } }`.
- `items`는 `sortOrder` 오름차순, 활성 항목만 포함한다.
- `additionalProperties: false` 계약에 맞춰 `code`·`displayName` 외 필드를 직렬화하지 않는다.
- 이 세 경로는 쓰기 트랜잭션·잠금을 획득하지 않는다.

## 교차 도메인 의존성·조율 (구현 전 확인)

이 산출물의 catalog 도메인 코드·마이그레이션·검증 메서드·테스트는 지금 독립 구현이 가능하다. 다만 다음 두 항목은 다른 소유권과 맞닿아 있어 가짜 구현으로 우회하지 않고 명시한다.

1. **공개 경로 비회원 접근(1번 인증 소유).** 정책 AUTH-001은 비회원 공개 탐색·조회를 허용한다. 그러나 실제 런타임에서 이 세 경로를 익명 허용하는 `SecurityFilterChain`은 1번 인증 도메인이 소유하며 아직 `dev`에 없다(현재 spring-security는 classpath에만 존재). 따라서:
   - 이 산출물은 **production `SecurityFilterChain`을 만들지 않는다.**
   - Controller의 "인증 없이 200"은 기존 하우스 패턴(`MockMvcBuilders.standaloneSetup`)으로 slice 검증한다. 이는 #31 인수 조건("MockMvc로 인증 없이 200")을 충족한다.
   - 전체 앱에서의 익명 서빙은 1번의 `SecurityFilterChain`이 공개 경로 목록을 허용할 때 성립하는 통합 의존성으로 남긴다. 이 상태는 `dev`의 현재 상태와 동일하며 #31이 악화시키지 않는다.
2. **Flyway 버전 번호 규약(공유 폴더).** `backend/src/main/resources/db/migration`은 전 도메인이 공유한다. 여러 도메인이 병렬로 첫 migration을 추가하면 단순 순번(`V1`, `V2`)은 병합 시 충돌한다. #31이 최초로 이 폴더를 사용하므로 규약이 필요하다.
   - **권장:** 날짜 기반 버전 `V2026_07_29_01__...` 형식으로 도메인 간 충돌을 회피한다.
   - 이 규약은 공통 기반 성격이므로 #32(공통 MySQL 기반)·팀 확인이 이상적이다. 미확정이면 이 산출물은 권장 형식으로 진행하되 규약을 문서에 남기고, 확정 시 후속 migration부터 정렬한다.

## Flyway migration 설계

`backend/src/main/resources/db/migration` 아래 두 파일로 나눈다(스키마와 seed 거버넌스 분리).

- `V2026_07_29_01__create_catalog_tables.sql` — `catalog_item`, `catalog_version` DDL, 유일 제약·인덱스, `utf8mb4`와 명시 collation `utf8mb4_0900_ai_ci`(유일 제약 동작을 서버 기본값에 의존하지 않도록 고정, 코드 리뷰 반영).
- `V2026_07_29_02__seed_catalog_mvp1.sql` — 승인된 8/10/8 seed와 세 종류 `version = 1` INSERT.

- 빈 MySQL에서 clean-start로 스키마와 seed를 재현한다.
- 적용된 두 파일은 이후 수정하지 않는다. 코드 추가·비활성은 새 `V...` 파일로만 한다.
- 버전 번호는 위 규약 확정 결과에 맞춘다.

## 의존성

`backend/build.gradle.kts`에 다음을 추가한다. 버전은 Spring Boot 의존성 관리(spring-boot-dependencies:4.1.0)가 단일 소유하므로 명시하지 않는다(ADR-004).

production — Flyway autoconfig 모듈. Spring Boot 4는 autoconfig가 모듈로 분리되어 `flyway-core`만으로는 마이그레이션이 자동 실행되지 않는다(구현 중 확인된 스캐폴드 결함). 첫 마이그레이션을 도입하는 #31이 보정한다.

```kotlin
implementation("org.springframework.boot:spring-boot-flyway")
```

test 스코프 — Testcontainers MySQL. Testcontainers 2.0에서 모듈명에 `testcontainers-` 접두사가 붙었고, Spring Boot 4.1.0 BOM이 버전을 관리하므로 별도 버전·BOM import가 필요 없다.

```kotlin
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:testcontainers-junit-jupiter")
testImplementation("org.testcontainers:testcontainers-mysql")
```

- 그 밖의 production 의존성·플러그인은 추가하지 않는다.
- Lombok을 추가하지 않고 record·명시적 생성자·접근자를 사용한다.

## 테스트 설계

### 단위 테스트 — `CatalogServiceTest` (Mockito)

- `getItems`가 활성 항목을 `sortOrder` 오름차순으로 반환한다.
- 비활성 항목이 조회·검증에서 제외된다.
- `isActiveCode`가 활성 코드에 `true`, 미승인·비활성 코드에 `false`.
- `findUnknownCodes`가 미승인·비활성 코드만 반환하고 전부 유효하면 빈 목록을 반환한다.

### 웹 slice 테스트 — `CatalogControllerTest` (standalone MockMvc)

- 기존 하우스 패턴 `MockMvcBuilders.standaloneSetup(new CatalogController(...))`으로 Security 없이 구성한다.
- 세 경로가 인증 없이 `200`을 반환한다.
- 응답이 `code=SUCCESS`, `data.items[*].code`, `data.items[*].displayName`을 포함한다.
- `items`가 정렬 순서를 유지한다.
- `code`·`displayName` 외 필드가 직렬화되지 않는다.

### 통합 테스트 — `CatalogRepositoryIT` (Testcontainers MySQL)

- `@SpringBootTest` + `@Testcontainers` + `MySQLContainer` + `@ServiceConnection`으로 실제 MySQL에 Flyway migration을 적용한다.
- Flyway clean-start 후 세 종류의 활성 seed 행 수가 8/10/8이다.
- `catalog_version`이 세 종류 `version = 1`을 가진다.
- `(kind, code)` 유일 제약 위반 시 무결성 예외가 발생한다.
- `findByKindAndActiveTrueOrderBySortOrderAsc`가 활성 항목만 정렬해 반환한다.
- H2·인메모리 성공을 MySQL 통합 증거로 대체하지 않는다.

### 회귀 검증

```powershell
cd backend
.\gradlew.bat test
.\gradlew.bat build
git diff --check
```

- 첫 영속성 계층 도입으로 기존 `MiriyumApplicationTests`·`ApplicationJacksonConfigurationTest`는 DataSource·JPA 제외 컨텍스트를 더 이상 로드할 수 없어 Testcontainers 기반 실제 컨텍스트 테스트로 승격한다(ADR-004 기준선, 아래 allowlist 확장). `ApplicationYamlTest`는 컨텍스트를 로드하지 않아 영향받지 않는다.
- 이 산출물은 매장·메뉴 API, 인증, 검색이 동작함을 주장하지 않는다.

## 파일 허용 목록 (#31)

- `backend/build.gradle.kts` — Flyway autoconfig 모듈(production) + Testcontainers MySQL 테스트 의존성
- `backend/src/main/resources/db/migration/**`
- `backend/src/main/java/com/miriyum/store/catalog/**`
- `backend/src/test/java/com/miriyum/store/catalog/**`
- `backend/src/test/resources/**` — 통합 테스트 입력만
- `docs/superpowers/specs/2026-07-29-store-catalog-design.md`
- `docs/superpowers/plans/*store-catalog*.md`

### 승인된 allowlist 확장 (2026-07-29)

첫 영속성 계층 도입이 아래 global 컨텍스트 테스트를 깨뜨려, global 기반 소유자 승인으로 #31 범위에 포함하고 Testcontainers 기반으로 승격한다.

- `backend/src/test/java/com/miriyum/MiriyumApplicationTests.java`
- `backend/src/test/java/com/miriyum/global/config/ApplicationJacksonConfigurationTest.java`

그 밖의 허용 목록 밖 파일이 필요하면 Issue #31 범위·계약을 먼저 갱신한다.

## 구현 및 통합 순서

1. 이 설계 문서를 `feature/31-store-catalog`에 커밋한다.
2. 상세 구현 계획(TDD 순서 포함)을 작성·승인받는다.
3. 실패하는 `CatalogServiceTest`(조회·검증)를 먼저 작성한다.
4. 실패하는 `CatalogControllerTest`(slice)를 작성한다.
5. 실패하는 `CatalogRepositoryIT`(Testcontainers MySQL·Flyway·제약)를 작성한다.
6. 최소 production 코드(엔티티·enum·repository·service·controller·DTO)를 구현한다.
7. Flyway migration(DDL·seed)을 추가한다.
8. Testcontainers 테스트 의존성을 추가한다.
9. 관련 테스트와 전체 backend build를 실행한다.
10. allowlist·diff·계약 정합성을 검토한다.
11. Draft PR을 `dev` 대상으로 열고 작성자를 제외한 검토를 요청한다.

## 위험과 롤백

- **seed 거버넌스:** 승인된 8/10/8 외 값을 임의 추가·변경하지 않는다. 추가·비활성은 새 migration으로만 하고 기존 code 의미를 재사용하지 않는다.
- **Security 경계:** production `SecurityFilterChain`을 만들지 않아 1번 인증 구현과 충돌하지 않는다. 전체 앱 익명 서빙은 1번 도메인 병합 후 통합 검증한다. 이 산출물의 완료 주장에 "익명 서빙 전체 동작"을 포함하지 않는다.
- **Flyway 번호 규약:** 규약이 확정되기 전 병합되면 다른 도메인 첫 migration과 충돌할 수 있다. 날짜 기반 규약을 사용하고 문서에 남긴다.
- **버전 모델:** `catalog_version`은 미노출 거버넌스 표식이다. 응답 계약(`CatalogListData`)에 버전 필드를 추가하지 않는다.
- **롤백:** 문제가 생기면 Issue #31 PR 전체를 되돌릴 수 있다. 도메인별 임시 catalog 하드코딩이나 공통 봉투 재정의는 롤백 대안이 아니다.
