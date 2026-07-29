# 매장·메뉴 카테고리와 매장 태그 catalog 기반 설계

> 추적 Issue: [#31](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/31)
> 기준 브랜치: `dev` (기준 SHA `1a2e175`, PR #30 병합 상태)
> 기준 계약: PR #27 병합 `docs/specs/store-search/{spec.md,openapi.yaml}`, PR #30 병합 공통 `ApiResponse`·`ErrorResponse`·`ServiceException`
> 정책 원천: `docs/service-policies/03-store-operation.md` (OPER-002~006 초기 고정 후보)
> 아키텍처 기준: `docs/adr/ADR-001-domain-packages-three-layer.md`, `docs/specs/mvp1-common/ownership.md` (2번 도메인 루트 `com.miriyum.domain.store`)
> seed·code·version·스키마 승인일: 2026-07-29 (매장 도메인 소유자 @116Lv)
> 설계 상태: 승인(코드 리뷰 반영 후 개정)

## 목적

2번 담당 영역의 첫 독립 산출물로, 승인된 매장 카테고리·메뉴 카테고리·매장 태그 catalog를 MySQL 정본으로 관리하고 비회원 공개 조회 API를 제공한다. 후속 매장·메뉴 기능(#33, #35)이 재사용할 **catalog 코드 검증 공개 메서드**를 제공한다.

매장·메뉴 Entity, 등록·수정 API, 운영자 인증·인가, 영업시간, 예약·수량 가용성, 검색과 관리자용 catalog 수정 API는 범위가 아니다.

## 설계 원칙

- catalog code는 **불투명한 문자열**이다. 표시명에서 추측·변환하지 않는다.
- seed 값은 발명하지 않는다. `03-store-operation.md`의 초기 고정 후보에서 최소 MVP 부분집합을 승인받아 사용한다.
- 아키텍처는 ADR-001 3계층과 ownership.md의 도메인 루트를 따른다: `com.miriyum.domain.store`의 `controller`/`service`/`repository`/`entity`/`dto`.
- catalog 검증 메서드는 "이 종류에 이 코드가 활성으로 존재하는가"만 판정하는 **중립 결과**를 반환한다. `STORE_004` 매핑, 개수·중복 규칙은 소비 도메인(#33, #35)이 소유한다.
- 공통 `ApiResponse`·`ErrorResponse`·`ServiceException`을 재사용한다.
- `SecurityFilterChain`·JWT·인증은 1번 인증 도메인 소유이므로 이 산출물에서 만들지 않는다.
- Flyway migration은 적용(병합) 후 수정하지 않는다. 코드 추가·비활성은 새 migration으로 수행한다.
- DB 동작은 H2가 아니라 Testcontainers MySQL로 검증한다(ADR-002·ADR-004).

## 승인 기록 (2026-07-29, 매장 도메인 소유자)

정책 `03-store-operation.md` 초기 고정 후보의 최소 부분집합. 한글 후보를 불투명 영문 code로 매핑하고, 세 catalog 모두 초기 `version = 1`. 물리 구조는 **종류별 테이블 분리**로 승인.

### 매장 카테고리 `store_category` — 8개

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

유보(후속 migration): `분식·간편식`, `퓨전`.

### 메뉴 카테고리 `menu_category` — 10개

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

유보: `찜 요리`, `치킨`, `분식·사이드`, `주류`.

### 매장 태그 `store_tag` — 8개

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

의도적 제외: 이용 방식 태그(`예약 가능`·`웨이팅 가능`·`메뉴 홀드 가능`·`포장 가능`·`픽업 가능`)는 `modes`·`pickupEligibility` 구조화 축이 소유하고, `웨이팅 가능`은 고도화 기능이며, `대표 메뉴`는 메뉴별 `representative`다. `캐주얼`·`프리미엄`·`매운맛`·`주류 판매`·`노키즈존`·`한정 수량`·`시즌 메뉴`·`점심 특화`·`저녁 특화`는 유보 후 후속 migration으로 추가한다.

## 데이터 모델

동일 형태를 **종류별 테이블 3개**로 분리한다. `code`를 자연 기본키로 두어 후속 `Store.store_category_code`가 `store_category.code`로 단독 FK를 걸 수 있게 한다(범용 단일 테이블의 `(kind, code)`·`ETC` 중복 문제 회피).

### `store_category` / `menu_category` / `store_tag` (동일 구조)

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `code` | `VARCHAR(50)` `COLLATE utf8mb4_0900_as_cs` | PK | 불투명 코드, 패턴 `^[A-Z][A-Z0-9_]{1,49}$`, **대소문자 구분** |
| `display_name` | `VARCHAR(100)` | NOT NULL | 한글 표시명 |
| `active` | `BOOLEAN` | NOT NULL | 공개·검증 대상 여부 |
| `sort_order` | `INT` | NOT NULL, `CHECK (sort_order > 0)` | 종류 안 표시 순서 |

- `code`는 자연 PK이자 대소문자 구분 collation → 애플리케이션 검증(Java `equals`)과 DB 비교가 **일치**한다(`korean` != `KOREAN`).
- 정렬은 `ORDER BY sort_order ASC, code ASC`로 결정적이다(동순번 시 code로 안정 정렬).
- 테이블 기본 문자셋 `utf8mb4`/`utf8mb4_0900_ai_ci`(한글 표시명), `code` 컬럼만 `as_cs` 재정의.

### `catalog_version`

| 컬럼 | 타입 | 제약 | 의미 |
|---|---|---|---|
| `catalog` | `VARCHAR(30)` | PK | `store_category`·`menu_category`·`store_tag` |
| `version` | `BIGINT` | NOT NULL, `CHECK (version > 0)` | 현재 활성 seed 버전 |

- 초기 3행 모두 `version = 1`. OpenAPI 공개 응답(`CatalogListData = {items}`)에는 버전 필드가 없어 노출하지 않는 seed 거버넌스 표식이다. 2차 MVP 검색 캐시 키가 소비할 수 있다.

## 패키지 구조

```text
com.miriyum.domain.store
├─ controller
│  └─ CatalogController
├─ service
│  ├─ CatalogService        # 조회 + 검증 공개 메서드(후속 도메인 재사용점)
│  ├─ CatalogKind           # enum: STORE_CATEGORY, MENU_CATEGORY, STORE_TAG (API 종류 선택자)
│  └─ CatalogItemView       # 조회 결과 내부 표현(code, displayName)
├─ repository
│  ├─ CatalogEntryRepository    # @NoRepositoryBean 공통 조회 계약
│  ├─ StoreCategoryRepository
│  ├─ MenuCategoryRepository
│  └─ StoreTagRepository
├─ entity
│  ├─ CatalogEntry          # @MappedSuperclass (code PK, displayName, active, sortOrder)
│  ├─ StoreCategory
│  ├─ MenuCategory
│  └─ StoreTag
└─ dto/response
   ├─ CatalogItemResponse
   └─ CatalogListResponse
```

- `CatalogService`는 별도 유지한다. 근거: catalog는 응집된 하위 기능이고 #33/#35가 재사용하므로 `StoreService`(등록·수정·권한, 후속 Issue)와 책임을 분리한다. 정본에 "StoreService 우선" 명문 규칙은 없다.
- `CatalogKind`·`CatalogItemView`는 서비스 계약의 일부이므로 `service`에 둔다. `CatalogItemView`는 컨트롤러만 소비하는 내부 뷰이며 교차 도메인 공개 DTO가 아니다(소비 도메인은 검증 메서드만 사용).

## 도메인·엔티티 설계

- `CatalogEntry`(`@MappedSuperclass`)가 code(PK)·displayName·active·sortOrder를 공유하고, `StoreCategory`/`MenuCategory`/`StoreTag`가 각 `@Table`로 상속한다(DRY, 종류별 테이블 유지).
- seed 정본이 원천이며 런타임에서 생성·수정하지 않는다. 자연키 엔티티라 `repository.save`는 merge로 동작하므로, DB PK 유일성은 raw insert로 검증한다.

## Repository

- `@NoRepositoryBean CatalogEntryRepository<T extends CatalogEntry> extends JpaRepository<T, String>`
  - `findByActiveTrueOrderBySortOrderAscCodeAsc()`
  - `existsByCodeAndActiveTrue(String code)`
  - `findByActiveTrueAndCodeIn(Collection<String> codes)`
- 구체 `StoreCategoryRepository`/`MenuCategoryRepository`/`StoreTagRepository`가 상속(빈 인터페이스). 파생 쿼리만 사용.

## Service

`CatalogService`가 조회와 검증을 제공한다. 종류→저장소 매핑은 `switch(CatalogKind)`.

- 조회: `List<CatalogItemView> getItems(CatalogKind)`.
- 검증(중립): `boolean isActiveCode(CatalogKind, String)`, `List<String> findUnknownCodes(CatalogKind, Collection<String>)`.
- `ServiceException`·`STORE_004`를 던지지 않는다. 소비 도메인이 결과를 받아 매핑한다.
- DB `as_cs` collation과 Java `equals`가 일치하여 단건·다건 검증이 대소문자에 동일하게 반응한다.

## 공개 API 계약

| 메서드·경로 | operationId | 응답 data |
|---|---|---|
| `GET /api/v1/store-categories` | `getStoreCategories` | `CatalogListData` |
| `GET /api/v1/menu-categories` | `getMenuCategories` | `CatalogListData` |
| `GET /api/v1/store-tags` | `getStoreTags` | `CatalogListData` |

- `ApiResponse.success(message, CatalogListResponse)` → `{code:"SUCCESS", message, data:{items:[{code, displayName}]}}`.
- 활성 항목만, `sort_order`→`code` 오름차순. `code`·`displayName` 외 필드는 직렬화하지 않는다.

## 교차 도메인 의존성·조율

1. **공개 경로 비회원 접근(1번 인증 소유).** 정책 AUTH-001은 비회원 조회를 허용하지만, 실제 런타임에서 이 경로들을 익명 허용하는 `SecurityFilterChain`은 1번 도메인 소유이며 아직 `dev`에 없다(spring-security는 classpath에만 존재해 기본 설정상 익명 요청은 401). 따라서:
   - 이 산출물은 **production `SecurityFilterChain`을 만들지 않는다.**
   - `CatalogControllerTest`는 `standaloneSetup`(Security 미포함)으로 **컨트롤러 매핑·봉투 직렬화만** 검증한다. 이는 "익명 200"의 증명이 아니다.
   - 실제 익명 서빙은 1번의 `SecurityFilterChain`이 세 경로를 `permitAll` 하고, 실제 FilterChain을 포함한 통합 테스트로 익명 200을 검증한 뒤 성립한다. #40은 그 전까지 완료로 표시하지 않는다.
2. **Flyway 버전 규약.** 공유 `db/migration`은 날짜 기반 `V2026_07_29_01__` 형식으로 도메인 간 충돌을 회피한다.

## Flyway migration 설계

- `V2026_07_29_01__create_catalog_tables.sql` — `store_category`·`menu_category`·`store_tag`(각 code PK·`as_cs`·`CHECK(sort_order>0)`)와 `catalog_version`(`CHECK(version>0)`).
- `V2026_07_29_02__seed_catalog_mvp1.sql` — 승인 seed 8/10/8 + 세 종류 `version = 1`.
- 빈 MySQL clean-start로 재현. 적용(병합) 후 파일 불변, 변경은 새 migration.

## 의존성

버전은 Spring Boot 의존성 관리(spring-boot-dependencies:4.1.0)가 단일 소유(ADR-004).

production — Flyway autoconfig 모듈(Spring Boot 4 autoconfig 모듈 분리로 `flyway-core` 단독으로는 마이그레이션 미실행, 구현 중 확인된 스캐폴드 결함 보정):

```kotlin
implementation("org.springframework.boot:spring-boot-flyway")
```

test — Testcontainers MySQL(2.0에서 `testcontainers-` 접두사, Spring Boot BOM이 버전 관리):

```kotlin
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:testcontainers-junit-jupiter")
testImplementation("org.testcontainers:testcontainers-mysql")
```

## 테스트 설계

- 단위 `CatalogServiceTest`(Mockito, 3 repository mock): 종류별 저장소 선택, 정렬 매핑, `isActiveCode`(활성/미승인/null), `findUnknownCodes`(미승인만/전부유효/빈입력).
- slice `CatalogControllerTest`(`standaloneSetup`): 세 경로 200, 공통 봉투·필드·정렬·추가필드 없음. Security 미포함이므로 응답 형태 검증 전용(인증 주장 없음).
- 통합 `CatalogRepositoryIT`(Testcontainers MySQL, `@ServiceConnection`, `@Transactional` 롤백, `mysql:8.0.40`):
  - 세 종류 전체 seed의 code·표시명·순서(`containsExactly`)
  - `catalog_version` 세 행 = 1
  - 자연키 code 중복 raw insert → 무결성 예외
  - 대소문자 구분(`korean` 미매치, `KOREAN` 매치)
  - 비활성 항목 활성 조회 제외
- 회귀: `./gradlew clean build`(Docker 필요). `disabledWithoutDocker`로 Docker 없으면 DB 테스트가 skip되므로, **병합 증거는 Docker 환경에서 0 skipped로 수집**한다.

## 파일 허용 목록 (#31, 정정판)

Issue #31 원안 allowlist(`com/miriyum/store/catalog/**`)는 ADR-001·ownership.md와 충돌하여 아래로 정정한다(Issue 본문 동기화 대상).

- `backend/build.gradle.kts` — Flyway autoconfig(production) + Testcontainers 테스트 의존성
- `backend/src/main/resources/db/migration/**`
- `backend/src/main/java/com/miriyum/domain/store/**`
- `backend/src/test/java/com/miriyum/domain/store/**`
- `docs/superpowers/specs/2026-07-29-store-catalog-design.md`
- `docs/superpowers/plans/*store-catalog*.md`

### 승인된 allowlist 확장 (2026-07-29, global 기반 소유자)

첫 영속성 계층이 DataSource·JPA 제외 컨텍스트 테스트를 무효화하여 Testcontainers 기준선으로 승격.

- `backend/src/test/java/com/miriyum/MiriyumApplicationTests.java`
- `backend/src/test/java/com/miriyum/global/config/ApplicationJacksonConfigurationTest.java`

## 위험과 롤백

- seed 거버넌스: 승인된 8/10/8 외 값을 임의 추가·변경하지 않는다. 추가·비활성은 새 migration으로만.
- Security 경계: production `SecurityFilterChain` 미생성으로 1번 인증과 충돌하지 않는다. #40 완료 주장에 "익명 서빙 전체 동작"을 포함하지 않으며 1번 병합 후 통합 검증한다.
- Flyway 번호: 날짜 기반 규약으로 병렬 도메인 충돌 회피.
- 자연키 merge: `repository.save`가 merge로 동작하므로 런타임 신규 삽입 경로가 없는 seed 전용 catalog에 한해 안전하며, DB PK가 무결성 backstop이다.
- 롤백: 문제 시 #31 PR 전체를 되돌릴 수 있다. 도메인별 임시 catalog 하드코딩은 롤백 대안이 아니다.
