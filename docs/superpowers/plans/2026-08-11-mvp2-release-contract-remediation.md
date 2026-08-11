# MVP2 Release Contract Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 메뉴 대안 검색 경로를 audience/aggregate OpenAPI 진입점에 노출하고 지오코딩 migration 테스트 표시명을 V29로 정정한다.

**Architecture:** 기능별 `store-search/openapi.yaml`을 단일 원본으로 유지하고, 공개 진입점과 aggregate는 단일 `$ref`만 추가한다. 기존 audience 계약 테스트를 확장해 기능별 공개 경로 누락을 회귀 검출한다.

**Tech Stack:** OpenAPI 3.1 YAML, Java 21, JUnit 5, AssertJ, Gradle

## Global Constraints

- runtime API와 schema는 변경하지 않는다.
- 생성 TypeScript 파일은 수동 편집하지 않는다.
- Flyway migration 파일은 변경하지 않는다.

---

### Task 1: 공개 OpenAPI 진입점 정합화

**Files:**
- Modify: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- Modify: `docs/specs/public-openapi.yaml`
- Modify: `docs/specs/mvp1-openapi.yaml`

**Interfaces:**
- Consumes: `store-search/openapi.yaml`의 `/api/v1/stores/{storeId}/menus/{menuId}/alternatives/search` path item
- Produces: public 및 aggregate 진입점의 동일 경로 단일 `$ref`

- [ ] **Step 1: Write the failing test**

`audienceEntrypointsPartitionTheAggregatePaths`에서 위 경로가 public과 aggregate에 모두 포함되는지 literal로 검증한다.

- [ ] **Step 2: Run test to verify it fails**

Run: `.\gradlew.bat test --tests "com.miriyum.architecture.AudienceOpenApiContractTest" --rerun-tasks`
Expected: 누락 경로 assertion 실패.

- [ ] **Step 3: Write minimal implementation**

두 YAML 진입점에 path와 단일 `$ref`를 각각 추가한다.

- [ ] **Step 4: Run test to verify it passes**

Run: `.\gradlew.bat test --tests "com.miriyum.architecture.AudienceOpenApiContractTest" --rerun-tasks`
Expected: PASS.

### Task 2: 지오코딩 migration 표시명 정정과 전체 검증

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/repository/StoreGeocodingMigrationTest.java`

**Interfaces:**
- Consumes: 실제 migration `V29__add_store_geocoding.sql`
- Produces: CI 보고서에서 실제 버전과 일치하는 테스트 표시명

- [ ] **Step 1: Rename display labels**

두 `@DisplayName`의 `V23`을 `V29`로 바꾼다.

- [ ] **Step 2: Run verification**

Run: `.\gradlew.bat test assemble --rerun-tasks`, `pnpm generate:api`, `pnpm typecheck`, `pnpm test`, `pnpm build`, `git diff --check`.
Expected: 모든 명령 종료 코드 0, 생성 파일 diff 없음.

- [ ] **Step 3: Commit**

명시된 네 구현/테스트 파일과 이 설계·계획 문서만 stage하고 `fix(api): MVP2 공개 계약 진입점 정합화`로 commit한다.
