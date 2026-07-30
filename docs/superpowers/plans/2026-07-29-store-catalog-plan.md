# 매장·메뉴 catalog 기반 + 공개 경로 Security 구현 계획 (#31)

> 추적 Issue: [#31](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/31)
> 설계: `docs/superpowers/specs/2026-07-29-store-catalog-design.md` (승인)
> 브랜치: `feature/31-store-catalog` (rebase 기준 `ade5a93`: PR #30 공통 + PR #56 인증(consumer))
> 방식: TDD, Testcontainers MySQL 검증

## 전제·기준

- 최신 `dev`(`ade5a93`) 위로 rebase. dev의 1번 인증(`SecurityConfig` 다중 필터체인·JWT·`V1__create_consumer_accounts`)과 `spring-boot-starter-flyway`를 기준으로 한다.
- seed·code·version·물리 스키마 Human 승인 완료(2026-07-29): 매장 8 / 메뉴 10 / 태그 8.
- 아키텍처: ADR-001 3계층 + ownership.md L369 → `com.miriyum.domain.store`.
- **Docker 필요:** 모든 통합·보안·스모크 테스트가 Testcontainers MySQL(`mysql:8.0.40`). 카탈로그·Flyway DB 증거에는 H2를 사용하지 않는다.

## 산출물 구조 (com.miriyum.domain.store)

```
entity/CatalogEntry(@MappedSuperclass), StoreCategory, MenuCategory, StoreTag
repository/CatalogEntryRepository(@NoRepositoryBean) + 종류별 3
service/CatalogService, CatalogKind, CatalogItemView
controller/CatalogController (3 GET)
dto/response/CatalogItemResponse, CatalogListResponse
config/CatalogSecurityConfig (store 전용 공개 SecurityFilterChain)
db/migration/V2__create_catalog_tables.sql, V3__seed_catalog_mvp1.sql
```

## TDD 단계

1. 단위 `CatalogServiceTest`(9) — 종류별 조회·정렬, `isActiveCode`, `findUnknownCodes`(중립 결과 · **null 원소 거부 계약(`IllegalArgumentException`)**, STORE_004는 소비 도메인).
2. slice `CatalogControllerTest`(3, `standaloneSetup`) — 응답 봉투·필드·정렬·추가필드 없음(응답 형태 전용).
3. GREEN production — entity(@MappedSuperclass+3)·repository(base+3)·service·controller·dto.
4. Migration — 종류별 테이블 3개(code 자연 PK, `as_cs` 대소문자 구분, `CHECK(sort_order>0)`), seed 8/10/8. 런타임 버전 테이블 없음(YAGNI). Flyway 순서 `V1`(인증) → catalog create → catalog seed.
5. 통합 `CatalogRepositoryIT`(8, Testcontainers MySQL) — Flyway clean-start·전체 seed·자연키 유일·**`sort_order<=0` CHECK**·**code 형식 CHECK(소문자·숫자시작·특수문자·한글·빈문자열·50자초과 6종 거부)**·대소문자·비활성.
6. **Security(RED→GREEN)** — `config/CatalogSecurityConfig`에 store 전용 `SecurityFilterChain`(`@Order(0)`) 추가. 1번의 도메인 확장 지점 사용, **중앙 `SecurityConfig` 미수정**.
   - securityMatcher = catalog 경로군(정확한 3경로 + 각 `/**`) → 유사 경로도 이 체인이 소유.
   - `permitAll` = 정확한 세 GET 경로만. 그 밖의 method·유사 경로 = `denyAll`.
   - 1번 `JwtAuthenticationEntryPoint`(401)·`JwtAccessDeniedHandler`(403) 연결 → 공통 `ErrorResponse` envelope.
   - `CatalogSecurityIT`(5, 실제 SecurityFilterChain): 정확한 익명 GET 200+SUCCESS envelope / 익명 유사경로 401 `AUTH_001` / 인증 유사경로 403 `AUTH_006` / 정확 경로 비허용 method 401·403 / 잘못된·타 namespace 토큰에도 공개 GET 200. 오류 응답 Content-Type JSON.
7. 의존성 — dev의 `spring-boot-starter-flyway` 유지(마이그레이션 자동 실행) + test `spring-boot-testcontainers`/`testcontainers-junit-jupiter`/`testcontainers-mysql` 추가. 카탈로그·스모크 Testcontainers 테스트에 `miriyum.jwt.*` 설정 제공.

## 검증 명령

```powershell
cd backend
.\gradlew.bat clean build
git diff --check
```

## 완료 기준 (인수 조건 매핑) — 2026-07-30 `./gradlew clean build` 통과로 검증

- [x] 세 공개 API가 실제 SecurityFilterChain에서 **익명 GET 200 + SUCCESS envelope** → `CatalogSecurityIT` + `CatalogControllerTest`.
- [x] 보호/유사 경로 401·403이 공통 `ErrorResponse` envelope(`AUTH_001`/`AUTH_006`, JSON) → `CatalogSecurityIT`.
- [x] code 불투명·자연키 유일·대소문자 구분·`sort_order` CHECK → `CatalogRepositoryIT`.
- [x] 미승인/비활성 code 중립 검증 → `CatalogServiceTest`.
- [x] 빈 MySQL Flyway clean-start(`V1`→catalog) + 전체 seed 재현 → `CatalogRepositoryIT`(실제 MySQL, skip 아님).
- [x] 카탈로그·Flyway DB 증거에 H2 미사용 → Testcontainers MySQL만.
- [x] 공통 응답·예외 재사용.

전체: **99 tests, 0 failed, 0 skipped**. Testcontainers MySQL 실제 실행.

## 범위 밖·주의

- 매장·메뉴 Entity·등록·수정, 영업시간, 검색 없음(각 후속 Issue). 운영자 CRUD·감사·캐시·버전은 고도화.
- 1번 중앙 `SecurityConfig`는 수정하지 않는다(store 전용 체인만 추가).
- 적용(병합)된 Flyway 파일 수정 금지 — 보정은 새 migration.
- 병합은 작성자 외 사람 리뷰·브랜치 보호 충족 후에만. 작성자 self-approve·관리자 우회 금지.
