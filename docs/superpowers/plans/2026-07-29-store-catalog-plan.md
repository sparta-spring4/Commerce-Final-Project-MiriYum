# 매장·메뉴 catalog 기반 구현 계획 (#31)

> 추적 Issue: [#31](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/31)
> 설계: `docs/superpowers/specs/2026-07-29-store-catalog-design.md` (승인, 코드 리뷰 반영 개정)
> 브랜치: `feature/31-store-catalog` (base `1a2e175`)
> 방식: TDD, Testcontainers MySQL 검증

## 전제·선행 확인

- 선행 병합: PR #27(계약), PR #30(공통 응답·예외).
- seed·code·version·스키마 승인 완료(2026-07-29): 매장 8 / 메뉴 10 / 태그 8, v1, 종류별 테이블 분리.
- 아키텍처: ADR-001 3계층 + ownership.md 도메인 루트 `com.miriyum.domain.store`.
- Security는 만들지 않음(1번 소유). 다른 팀원 코드 의존 없음.
- **Docker 필요:** IT는 Testcontainers MySQL(`mysql:8.0.40`). Docker 없으면 `disabledWithoutDocker`로 skip → 병합 증거는 Docker 환경 0 skipped로 수집.

## 산출물 구조 (com.miriyum.domain.store)

```
entity/CatalogEntry(@MappedSuperclass), StoreCategory, MenuCategory, StoreTag
repository/CatalogEntryRepository(@NoRepositoryBean), StoreCategoryRepository, MenuCategoryRepository, StoreTagRepository
service/CatalogService, CatalogKind, CatalogItemView
controller/CatalogController
dto/response/CatalogItemResponse, CatalogListResponse
db/migration/V2026_07_29_01__create_catalog_tables.sql, V2026_07_29_02__seed_catalog_mvp1.sql
```

## TDD 단계

1. RED 단위 `CatalogServiceTest` — 종류별 저장소 선택·정렬 매핑, `isActiveCode`(활성/미승인/null), `findUnknownCodes`(미승인만/전부유효/빈입력).
2. RED slice `CatalogControllerTest` — `standaloneSetup`으로 세 경로 200·봉투·필드·정렬·추가필드 없음(응답 형태 전용, 인증 주장 없음).
3. GREEN production — entity(@MappedSuperclass+3), repository(base+3), service(switch 매핑), controller, dto.
4. Migration — 종류별 테이블 3개(code PK, `as_cs` 대소문자 구분, `CHECK(sort_order>0)`), seed 8/10/8. seed 버전 v1은 주석으로만 기록(런타임 버전 테이블 없음, YAGNI).
5. RED→GREEN 통합 `CatalogRepositoryIT` — Testcontainers MySQL(`@ServiceConnection`, `@Transactional`): 전체 seed code·표시명·순서, code 중복 raw insert 무결성 예외, 대소문자 구분, 비활성 제외.
6. 의존성 — production `spring-boot-flyway`, test `testcontainers-junit-jupiter`/`testcontainers-mysql`(버전 BOM 관리).
7. global 컨텍스트 테스트 승격(allowlist 확장, 소유자 승인) — `MiriyumApplicationTests`·`ApplicationJacksonConfigurationTest`를 Testcontainers 기반으로.

## 검증 명령

```powershell
cd backend
.\gradlew.bat clean build
git diff --check
```

## 완료 기준 (인수 조건 매핑) — 2026-07-29 `./gradlew clean build` 통과로 검증

- [x] 세 공개 API가 공통 봉투로 활성 항목만 반환 → `CatalogControllerTest` 3/3 (실제 익명 서빙은 1번 Security 통합 후 별도 검증).
- [x] code 불투명·자연키 유일·대소문자 구분 → `CatalogRepositoryIT` 중복 raw insert·대소문자 검증.
- [x] 미승인/비활성 code 중립 검증 결과(STORE_004는 소비 도메인) → `CatalogServiceTest` 8/8.
- [x] 빈 MySQL Flyway clean-start + 전체 seed 재현 → `CatalogRepositoryIT` 6/6 (실제 MySQL, skip 아님).
- [x] H2 미사용 → Testcontainers MySQL만.
- [x] 공통 응답·예외 재사용.

전체: 58 tests, 0 failed, 0 skipped. 3개 Testcontainers 컨텍스트 실제 실행.

## 범위 밖·주의

- 매장·메뉴 Entity·등록·수정, 인증, 영업시간, 검색 없음. production `SecurityFilterChain` 미생성.
- 적용(병합)된 Flyway 파일 수정 금지 — 보정은 새 migration.
- Issue #31 allowlist는 정본(ADR-001·ownership.md)에 맞춰 `com.miriyum.domain.store`로 정정(설계 문서·Issue 동기화).
