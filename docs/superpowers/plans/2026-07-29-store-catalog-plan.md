# 매장·메뉴 catalog 기반 구현 계획 (#31)

> 추적 Issue: [#31](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/31)
> 설계: `docs/superpowers/specs/2026-07-29-store-catalog-design.md` (승인)
> 브랜치: `feature/31-store-catalog` (base `1a2e175`)
> 방식: TDD (RED → GREEN → 정리), Testcontainers MySQL 검증

## 전제·선행 확인

- 선행 병합 완료: PR #27(계약), PR #30(공통 `ApiResponse`/`ErrorResponse`/`ServiceException`).
- seed 승인 완료(2026-07-29): 매장 8 / 메뉴 10 / 매장 태그 8, `version = 1`.
- 다른 팀원 코드 의존 없음. Security는 만들지 않음(1번 소유).
- **Docker 필요:** `CatalogRepositoryIT`가 Testcontainers MySQL을 사용한다. Docker 미가동 시 단위·slice 테스트는 진행하되 통합 테스트는 `NOT CONFIGURED`로 보고하고 임의로 성공 처리하지 않는다.

## 커밋 격리 전략 (다른 팀원과 겹침 최소화)

1. **전용 파일 먼저** — `store.catalog/**`, `db/migration/*catalog*.sql`, catalog 테스트. 겹침 0.
2. **공유 파일 마지막** — `backend/build.gradle.kts`의 Testcontainers 테스트 의존성 3줄 + `CatalogRepositoryIT`. 가산적 변경이라 병합 충돌 위험 최소.

(커밋은 사용자 요청 시에만 수행한다. 검증 통과 전 stage/commit 금지.)

## TDD 단계

### 1. RED — 단위 테스트 `CatalogServiceTest`

- `getItems(kind)`가 활성 항목을 `sortOrder` 오름차순 반환(Mock repository).
- 비활성 항목 제외.
- `isActiveCode` 활성=true, 미승인/비활성=false.
- `findUnknownCodes`가 미승인/비활성만 반환, 전부 유효 시 빈 목록.
- 컴파일을 위한 최소 도메인/서비스 스켈레톤 작성 → 어서션에서 실패(RED).

### 2. RED — slice 테스트 `CatalogControllerTest`

- `MockMvcBuilders.standaloneSetup(new CatalogController(serviceMock))` (Security 없음).
- 세 경로 인증 없이 200.
- `code=SUCCESS`, `data.items[*].code`, `data.items[*].displayName`, 정렬 유지, 추가 필드 없음.

### 3. GREEN — production 최소 구현

- `domain/CatalogKind` (enum: STORE_CATEGORY, MENU_CATEGORY, STORE_TAG)
- `domain/CatalogItem` (`@Entity`: id, kind, code, displayName, active, sortOrder)
- `repository/CatalogItemRepository` (파생 쿼리 2종)
- `service/CatalogItemView` (record: code, displayName)
- `service/CatalogService` (getItems, isActiveCode, findUnknownCodes)
- `web/dto/CatalogItemResponse`, `web/dto/CatalogListResponse`
- `web/CatalogController` (3 GET, `ApiResponse.success`)
- 단위·slice 테스트 GREEN 확인.

### 4. Flyway migration

- `V2026_07_29_01__create_catalog_tables.sql` — `catalog_item`(+유일제약·인덱스), `catalog_version`, `utf8mb4`.
- `V2026_07_29_02__seed_catalog_mvp1.sql` — 승인 seed 8/10/8 + `version=1` 3행.

### 5. RED→GREEN — 통합 테스트 `CatalogRepositoryIT` (Testcontainers MySQL)

- `mysql:8.0` 컨테이너 + `@ServiceConnection`으로 datasource 연결(코드 리뷰 반영, 앱 placeholder yaml과 정상 동작 확인). Flyway가 스키마 생성(ddl-auto 기본 none).
- Flyway clean-start 후 활성 seed 8/10/8, `catalog_version` 3행 = 1.
- `(kind, code)` 유일 제약 위반 시 무결성 예외.
- `findByKindAndActiveTrueOrderBySortOrderAsc` 활성·정렬 검증.

### 6. build.gradle.kts 의존성

production — Flyway autoconfig 모듈(Spring Boot 4 모듈 분리로 `flyway-core` 단독으로는 마이그레이션 미실행):

```kotlin
implementation("org.springframework.boot:spring-boot-flyway")
```

test — Testcontainers MySQL(2.0 모듈명 `testcontainers-` 접두사, Spring Boot BOM이 버전 관리):

```kotlin
testImplementation("org.springframework.boot:spring-boot-testcontainers")
testImplementation("org.testcontainers:testcontainers-junit-jupiter")
testImplementation("org.testcontainers:testcontainers-mysql")
```

### 7. 기존 global 컨텍스트 테스트 승격 (allowlist 확장, 소유자 승인 2026-07-29)

첫 영속성 계층이 DB 제외 컨텍스트 테스트를 깨뜨리므로 Testcontainers 기반으로 승격한다.

- `MiriyumApplicationTests`, `ApplicationJacksonConfigurationTest` — `@Testcontainers(disabledWithoutDocker = true)` + MySQL 컨테이너 + `@DynamicPropertySource`.
- `ApplicationYamlTest`는 컨텍스트 미로드로 변경 불필요.

## 검증 명령

```powershell
cd backend
.\gradlew.bat test
.\gradlew.bat build
git diff --check
```

## 완료 기준 (인수 조건 매핑) — 2026-07-29 `./gradlew clean build` 통과로 검증

- [x] 세 공개 API가 비회원에게 `ApiResponse`+OpenAPI 형태로 활성 항목만 반환 → `CatalogControllerTest` 3/3.
- [x] code는 불투명·종류 내 유일 → `CatalogRepositoryIT` 유일 제약·동일 code 다른 종류 공존 검증.
- [x] 미승인/비활성 code 검증 결과 제공(STORE_004 변환은 소비 도메인) → `CatalogServiceTest` 7/7.
- [x] 빈 MySQL에서 Flyway가 스키마+seed 재현 → `CatalogRepositoryIT` 5/5 (실제 MySQL 컨테이너, skip 아님).
- [x] H2를 MySQL 증거로 쓰지 않음 → Testcontainers MySQL만 사용.
- [x] 공통 응답·예외 재사용, 임시 envelope 없음 → `ApiResponse.success` 재사용.

전체 결과: 56 tests, 0 failed, 0 skipped. 3개 Testcontainers 컨텍스트 실제 실행.

## 범위 밖·주의

- 매장·메뉴 Entity, 등록·수정, 인증, 영업시간, 검색, 관리자 catalog 수정 API 없음.
- production `SecurityFilterChain` 만들지 않음.
- 적용된 Flyway 파일 수정 금지 — 보정은 새 migration.
- Docker 미가동 시 IT를 실행하지 않은 채 성공 주장하지 않음.
