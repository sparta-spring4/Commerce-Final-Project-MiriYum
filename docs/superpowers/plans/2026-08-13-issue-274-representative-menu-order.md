# Representative Menu Order Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 매장 운영자가 대표(인기) 메뉴 3~5개를 순서대로 관리하고, 공개 상세과 후속 예약금 계약이 같은 versioned 원장을 사용하게 한다.

**Architecture:** `RepresentativeMenuSetting` aggregate가 매장별 현재 항목과 version을 소유한다. 운영자 전체 교체와 메뉴 공개 불가 전이는 설정 행을 먼저 잠가 직렬화하고, 공개 조회는 설정 항목을 현재 게시 메뉴와 결합해 방어적으로 필터링한다.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring Data JPA/JDBC, MySQL 8, Flyway, Spring Security, MockMvc, Testcontainers, OpenAPI 3.1, Gradle 9.6.1

## Global Constraints

- 대표 메뉴와 인기 메뉴는 같은 제품 개념이며 공개 필드명은 기존 `representativeMenus`를 유지한다.
- 운영자 전체 교체는 정확히 3~5개만 허용하고 `expectedVersion`과 `Idempotency-Key`를 요구한다.
- `SOLD_OUT`과 시간대별 재고 0은 대표 선정을 유지한다.
- `HIDDEN`, `PAUSED`, `RETIRED`, 게시 버전 없음만 자동 해제한다.
- 기존 `menu_versions.representative`는 역사 값으로 보존하고 현재 판정에 사용하지 않는다.
- Store/Menu는 Payment·Reservation·MenuHold Entity·Repository를 직접 참조하지 않는다.
- migration은 #272의 V36 다음 `V37__create_representative_menu_settings.sql`이며 #272 병합 전 #274를 병합하지 않는다.
- 단계 2 exact allowlist가 #274에 반영된 뒤에만 production runtime을 수정한다.

---

## File Structure

### Create

- `backend/src/main/resources/db/migration/V37__create_representative_menu_settings.sql`: 설정·항목·감사 스키마와 DB 제약
- `backend/src/main/java/com/miriyum/domain/menu/entity/RepresentativeMenuSetting.java`: version·상태·항목 전이 aggregate
- `backend/src/main/java/com/miriyum/domain/menu/entity/RepresentativeMenuEntry.java`: 순서와 menu ID
- `backend/src/main/java/com/miriyum/domain/menu/entity/RepresentativeMenuAudit.java`: append-only 설정 감사
- `backend/src/main/java/com/miriyum/domain/menu/enums/RepresentativeMenuSettingStatus.java`: `UNCONFIGURED`, `CONFIGURED`, `REQUIRES_ATTENTION`
- `backend/src/main/java/com/miriyum/domain/menu/enums/RepresentativeMenuAuditActorType.java`: `OPERATOR`, `SYSTEM`
- `backend/src/main/java/com/miriyum/domain/menu/enums/RepresentativeMenuAuditEventType.java`: `REPLACED`, `AUTO_REMOVED`
- `backend/src/main/java/com/miriyum/domain/menu/repository/RepresentativeMenuSettingRepository.java`: lazy 생성, 설정 잠금, 공개 projection
- `backend/src/main/java/com/miriyum/domain/menu/repository/RepresentativeMenuAuditRepository.java`: 감사 저장
- `backend/src/main/java/com/miriyum/domain/menu/dto/storeoperator/RepresentativeMenuReplaceRequest.java`: version과 ordered public IDs
- `backend/src/main/java/com/miriyum/domain/menu/dto/storeoperator/RepresentativeMenuItemResponse.java`: 운영자 현재 메뉴 항목
- `backend/src/main/java/com/miriyum/domain/menu/dto/storeoperator/RepresentativeMenuSettingResponse.java`: 설정 응답
- `backend/src/main/java/com/miriyum/domain/menu/dto/contract/RepresentativeMenuItem.java`: 교차 도메인 최소 항목
- `backend/src/main/java/com/miriyum/domain/menu/dto/contract/RepresentativeMenuSnapshot.java`: 공개 Store/Menu snapshot
- `backend/src/main/java/com/miriyum/domain/menu/service/RepresentativeMenuCommandFingerprint.java`: 결정적 전체 교체 지문
- `backend/src/main/java/com/miriyum/domain/menu/service/RepresentativeMenuCommandResult.java`: HTTP status와 재생 가능한 설정 응답
- `backend/src/main/java/com/miriyum/domain/menu/service/RepresentativeMenuService.java`: 조회·전체 교체·자동 해제
- `backend/src/main/java/com/miriyum/domain/menu/service/RepresentativeMenuQueryService.java`: 공개 상세·교차 도메인 읽기
- `backend/src/main/java/com/miriyum/domain/menu/controller/storeoperator/RepresentativeMenuController.java`: 운영자 GET·PUT
- `backend/src/test/java/com/miriyum/domain/menu/entity/RepresentativeMenuSettingTest.java`
- `backend/src/test/java/com/miriyum/domain/menu/service/RepresentativeMenuServiceTest.java`
- `backend/src/test/java/com/miriyum/domain/menu/controller/storeoperator/RepresentativeMenuControllerTest.java`
- `backend/src/test/java/com/miriyum/domain/menu/repository/RepresentativeMenuRepositoryIT.java`
- `backend/src/test/java/com/miriyum/domain/menu/service/RepresentativeMenuConcurrencyIT.java`
- `backend/src/test/java/com/miriyum/domain/menu/RepresentativeMenuOpenApiContractTest.java`
- Public representative projection assertions are colocated in `backend/src/test/java/com/miriyum/domain/menu/repository/RepresentativeMenuRepositoryIT.java` so the CI shard creates one MySQL/Spring context for the #274 repository boundary.
- `frontend/src/shared/api/generated/store-search.ts`: OpenAPI generated contract artifact required by frontend CI

### Modify

- `backend/src/main/java/com/miriyum/domain/menu/repository/MenuRepository.java`: sorted menu locks and representative query projection
- `backend/src/main/java/com/miriyum/domain/menu/service/MenuCommandService.java`: 설정-first 잠금과 자동 해제 연결
- `backend/src/main/java/com/miriyum/domain/search/repository/StorePublicReadRepository.java`: 현재 membership·표시 순서 조회
- `backend/src/main/java/com/miriyum/domain/search/service/StorePublicQueryService.java`: 상세 대표 목록을 설정 순서로 조립
- `backend/src/test/java/com/miriyum/domain/menu/service/MenuCommandServiceIT.java`: 숨김·중지·종료 자동 해제
- `backend/src/test/java/com/miriyum/domain/menu/controller/storeoperator/MenuControllerTest.java`: 새 collaborator 회귀
- `backend/src/test/java/com/miriyum/domain/search/service/StorePublicQueryServiceTest.java`: 대표 목록 순서·부분 목록
- `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`: store-operator aggregate 경로
- `docs/service-policies/03-store-operation.md`: 대표/인기 단일 개념과 자동 해제·품절 경계
- `docs/specs/store-search/spec.md`: 운영자 및 공개 조회 기능 계약
- `docs/specs/store-search/openapi.yaml`: path·schema·응답
- `docs/specs/store-operator-openapi.yaml`: audience path `$ref`

---

### Task 1: Contract-first 정본과 OpenAPI

**Files:**
- Modify: `docs/service-policies/03-store-operation.md`
- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/store-search/openapi.yaml`
- Modify: `docs/specs/store-operator-openapi.yaml`
- Create: `backend/src/test/java/com/miriyum/domain/menu/RepresentativeMenuOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`

**Interfaces:**
- Produces: `GET|PUT /api/v1/store-operators/stores/{storeId}/representative-menus`, schemas `RepresentativeMenuSetting`, `RepresentativeMenuReplaceRequest`, enum `RepresentativeMenuSettingStatus`.

- [ ] **Step 1: Write failing exact-contract tests**

```java
assertThat(paths.path("/api/v1/store-operators/stores/{storeId}/representative-menus")
        .has("get")).isTrue();
assertThat(paths.path("/api/v1/store-operators/stores/{storeId}/representative-menus")
        .has("put")).isTrue();
assertThat(request.path("properties").path("menuIds").path("minItems").asInt()).isEqualTo(3);
assertThat(request.path("properties").path("menuIds").path("maxItems").asInt()).isEqualTo(5);
assertThat(root.toString()).doesNotContain("popularMenus");
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat test --tests com.miriyum.domain.menu.RepresentativeMenuOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest`

Expected: FAIL because the path and schemas do not exist.

- [ ] **Step 3: Add the approved policy/spec/OpenAPI contract**

Define request IDs as `PublicId` strings, `expectedVersion >= 0`, unique ordered `menuIds` with 3–5 entries, mandatory `Idempotency-Key`, 200 GET/PUT responses, existing `STORE_003`, `STORE_009`, `STORE_010`, `COMMON_008` failures, and no new `popularMenus` public field.

- [ ] **Step 4: Verify GREEN and lint**

Run: `.\gradlew.bat test --tests com.miriyum.domain.menu.RepresentativeMenuOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest`

Run: `npx --yes @redocly/cli@2.35.1 lint docs/specs/store-search/openapi.yaml`

Run: `npx --yes @redocly/cli@2.35.1 bundle docs/specs/store-operator-openapi.yaml --output backend/build/openapi-verification/store-operator-openapi.yaml`

Expected: focused tests PASS, store-search lint PASS, bundle completes with only documented pre-existing warnings.

- [ ] **Step 5: Commit**

```powershell
git add -- docs/service-policies/03-store-operation.md docs/specs/store-search/spec.md docs/specs/store-search/openapi.yaml docs/specs/store-operator-openapi.yaml backend/src/test/java/com/miriyum/domain/menu/RepresentativeMenuOpenApiContractTest.java backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java
git commit -m "docs(store): 대표 메뉴 관리 계약 확정"
```

### Task 2: V37 persistence aggregate

**Files:**
- Create all migration, entity, enum, repository files listed in File Structure
- Create: `backend/src/test/java/com/miriyum/domain/menu/entity/RepresentativeMenuSettingTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/menu/repository/RepresentativeMenuRepositoryIT.java`

**Interfaces:**
- Produces: `RepresentativeMenuSetting.replace(List<Long>)`, `remove(long)`, `RepresentativeMenuSettingRepository.ensureExists(long)`, `findByStoreIdForUpdate(long)`.

- [ ] **Step 1: Write entity and MySQL RED tests**

```java
setting.replace(List.of(11L, 12L, 13L));
assertThat(setting.getVersion()).isEqualTo(1L);
assertThat(setting.getStatus()).isEqualTo(CONFIGURED);
assertThat(setting.orderedMenuIds()).containsExactly(11L, 12L, 13L);

setting.remove(12L);
assertThat(setting.getVersion()).isEqualTo(2L);
assertThat(setting.getStatus()).isEqualTo(REQUIRES_ATTENTION);
```

Repository IT must attempt duplicate `(store_id, menu_id)` and duplicate `(store_id, display_order)` inserts and assert `DataIntegrityViolationException`.

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat test --tests com.miriyum.domain.menu.entity.RepresentativeMenuSettingTest`

Run: `.\gradlew.bat integrationTest --tests com.miriyum.domain.menu.repository.RepresentativeMenuRepositoryIT`

Expected: compile failure because aggregate and migration are absent.

- [ ] **Step 3: Implement V37 and minimal entities**

Use setting row `store_id` PK, domain `version`, `status`, `lock_version`; entry PK `(store_id, display_order)` plus unique `(store_id, menu_id)`; audit rows with before/after version/status, actor/event, trigger menu, ordered JSON snapshot, request ID and central timestamp. `replace` accepts only 3–5 distinct positive IDs; `remove` increments only when membership existed.

- [ ] **Step 4: Implement repository locking**

```java
@Modifying
@Query(value = "INSERT IGNORE INTO representative_menu_settings "
        + "(store_id, version, status, lock_version, created_at, updated_at) "
        + "VALUES (:storeId, 0, 'UNCONFIGURED', 0, UTC_TIMESTAMP(6), UTC_TIMESTAMP(6))",
        nativeQuery = true)
void ensureExists(@Param("storeId") long storeId);

@Lock(LockModeType.PESSIMISTIC_WRITE)
@EntityGraph(attributePaths = "entries")
@Query("select s from RepresentativeMenuSetting s where s.storeId = :storeId")
Optional<RepresentativeMenuSetting> findByStoreIdForUpdate(long storeId);
```

- [ ] **Step 5: Verify GREEN and commit**

Run the two focused commands from Step 2. Expected: PASS.

```powershell
git add -- backend/src/main/resources/db/migration/V37__create_representative_menu_settings.sql backend/src/main/java/com/miriyum/domain/menu/entity backend/src/main/java/com/miriyum/domain/menu/enums backend/src/main/java/com/miriyum/domain/menu/repository/RepresentativeMenuSettingRepository.java backend/src/main/java/com/miriyum/domain/menu/repository/RepresentativeMenuAuditRepository.java backend/src/test/java/com/miriyum/domain/menu/entity/RepresentativeMenuSettingTest.java backend/src/test/java/com/miriyum/domain/menu/repository/RepresentativeMenuRepositoryIT.java
git commit -m "feat(store): 대표 메뉴 설정 원장 추가"
```

### Task 3: 전체 교체 Service와 멱등성

**Files:**
- Create request/response/contract DTO, fingerprint and service files from File Structure
- Modify: `backend/src/main/java/com/miriyum/domain/menu/repository/MenuRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/menu/service/RepresentativeMenuServiceTest.java`

**Interfaces:**
- Produces: `RepresentativeMenuSettingResponse get(long operatorId, long storeId)` and `RepresentativeMenuCommandResult replace(long operatorId, long storeId, IdempotencyKey key, RepresentativeMenuReplaceRequest request)`.

- [ ] **Step 1: Write RED service tests**

Cover exactly 3 and 5 success; 2, 6, duplicate, foreign store, hidden, paused, retired, unpublished rejection; `SOLD_OUT` acceptance; version conflict; replayed idempotent result; ordered response.

```java
when(menuRepository.findAllByIdForUpdate(List.of(11L, 12L, 13L)))
        .thenReturn(List.of(selling(11L), soldOut(12L), selling(13L)));
var result = service.replace(7L, 3L, key,
        new RepresentativeMenuReplaceRequest(0, List.of("11", "12", "13")));
assertThat(result.data().items()).extracting(RepresentativeMenuItemResponse::menuId)
        .containsExactly("11", "12", "13");
```

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat test --tests com.miriyum.domain.menu.service.RepresentativeMenuServiceTest`

Expected: compile failure for missing service and DTOs.

- [ ] **Step 3: Implement minimal command/query service**

Use principal namespace `store-operator`, command type `REPRESENTATIVE_MENU_REPLACE`, resource type `REPRESENTATIVE_MENU_SETTING`, sorted menu lock acquisition, original request order for entries, `COMMON_008` on stale version, `STORE_009` on missing/foreign menu, and `STORE_010` on ineligible state.

- [ ] **Step 4: Verify GREEN and commit**

Run the focused test. Expected: PASS.

```powershell
git add -- backend/src/main/java/com/miriyum/domain/menu/dto backend/src/main/java/com/miriyum/domain/menu/service/RepresentativeMenuCommandFingerprint.java backend/src/main/java/com/miriyum/domain/menu/service/RepresentativeMenuService.java backend/src/main/java/com/miriyum/domain/menu/service/RepresentativeMenuQueryService.java backend/src/main/java/com/miriyum/domain/menu/repository/MenuRepository.java backend/src/test/java/com/miriyum/domain/menu/service/RepresentativeMenuServiceTest.java
git commit -m "feat(store): 대표 메뉴 전체 교체 구현"
```

### Task 4: 운영자 HTTP API

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/menu/controller/storeoperator/RepresentativeMenuController.java`
- Create: `backend/src/test/java/com/miriyum/domain/menu/controller/storeoperator/RepresentativeMenuControllerTest.java`

**Interfaces:**
- Consumes Task 3 Service signatures.
- Produces OpenAPI-matching GET/PUT JSON envelope.

- [ ] **Step 1: Write RED MockMvc tests**

Test unauthenticated 401, wrong audience 401/403 according to existing filter contract, owner success, `menuIds` size and duplicate 400, missing key 400, and service errors 403/404/409.

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat test --tests com.miriyum.domain.menu.controller.storeoperator.RepresentativeMenuControllerTest`

- [ ] **Step 3: Implement controller**

```java
@GetMapping
ApiResponse<RepresentativeMenuSettingResponse> get(...)

@PutMapping
ResponseEntity<ApiResponse<RepresentativeMenuSettingResponse>> replace(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable long storeId,
        @RequestHeader(value = "Idempotency-Key", required = false) String rawKey,
        @Valid @RequestBody RepresentativeMenuReplaceRequest request)
```

Base mapping is `/api/v1/store-operators/stores/{storeId}/representative-menus`. Reuse existing Store management security chain and `IdempotencyKey.parse`.

- [ ] **Step 4: Verify GREEN and commit**

Run focused controller and OpenAPI tests. Expected: PASS.

```powershell
git add -- backend/src/main/java/com/miriyum/domain/menu/controller/storeoperator/RepresentativeMenuController.java backend/src/test/java/com/miriyum/domain/menu/controller/storeoperator/RepresentativeMenuControllerTest.java
git commit -m "feat(store): 대표 메뉴 운영자 API 추가"
```

### Task 5: 공개 상세과 교차 도메인 읽기

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/search/repository/StorePublicReadRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/service/StorePublicQueryService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/search/service/StorePublicQueryServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/search/repository/StorePublicReadRepositoryIT.java`

**Interfaces:**
- Consumes: `RepresentativeMenuQueryService.getCurrent(long storeId)` returning `RepresentativeMenuSnapshot`.
- Produces: existing `PublicStoreDetail.representativeMenus` in configured order and `PublicMenu.representative` from current membership.

- [ ] **Step 1: Write RED public-read tests**

Cover order `13,11,12`, `SOLD_OUT` retained, hidden/paused/retired defensively absent, no settings empty, and `REQUIRES_ATTENTION` two-item result.

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat test --tests com.miriyum.domain.search.service.StorePublicQueryServiceTest`

Run: `.\gradlew.bat integrationTest --tests com.miriyum.domain.search.repository.StorePublicReadRepositoryIT`

- [ ] **Step 3: Implement projection and composition**

Join current setting entries to `menus` and current `PUBLISHED` version; order by `display_order`; require `VISIBLE`, not retired, and selling status in `SELLING`,`SOLD_OUT`. Public all-menu rows compute `representative` using membership, never `mv.representative`.

- [ ] **Step 4: Verify GREEN and commit**

Run commands from Step 2. Expected: PASS.

```powershell
git add -- backend/src/main/java/com/miriyum/domain/search/repository/StorePublicReadRepository.java backend/src/main/java/com/miriyum/domain/search/service/StorePublicQueryService.java backend/src/test/java/com/miriyum/domain/search/service/StorePublicQueryServiceTest.java backend/src/test/java/com/miriyum/domain/search/repository/StorePublicReadRepositoryIT.java
git commit -m "feat(store): 공개 대표 메뉴 순서 연결"
```

### Task 6: 메뉴 공개 불가 전이 자동 해제

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/menu/service/MenuCommandService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menu/service/MenuCommandServiceIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/menu/controller/storeoperator/MenuControllerTest.java`

**Interfaces:**
- Consumes: `RepresentativeMenuService.lockSetting(long storeId)` and `autoRemoveLocked(long storeId, long menuId, Instant now, String requestId)`.

- [ ] **Step 1: Write RED integration tests**

Create a configured three-menu setting, then verify HIDDEN, PAUSED and RETIRED each remove only the target, increment once, set `REQUIRES_ATTENTION`, and write one SYSTEM audit. Verify SOLD_OUT keeps all entries and `CONFIGURED`. Verify replay does not add another audit.

- [ ] **Step 2: Verify RED**

Run: `.\gradlew.bat integrationTest --tests com.miriyum.domain.menu.service.MenuCommandServiceIT`

- [ ] **Step 3: Implement consistent lock order and auto removal**

For visibility, selling-status and retirement callbacks: ensure/lock setting before `lockedMenu`; mutate menu; call auto removal only for resulting `HIDDEN`, `PAUSED` or retired state; save menu event and representative audit in the same transaction. Do not auto-reselect when state returns.

- [ ] **Step 4: Verify GREEN and commit**

Run focused IT and `MenuControllerTest`. Expected: PASS.

```powershell
git add -- backend/src/main/java/com/miriyum/domain/menu/service/MenuCommandService.java backend/src/test/java/com/miriyum/domain/menu/service/MenuCommandServiceIT.java backend/src/test/java/com/miriyum/domain/menu/controller/storeoperator/MenuControllerTest.java
git commit -m "feat(store): 비공개 대표 메뉴 자동 해제"
```

### Task 7: 실제 MySQL 경합 검증

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/menu/service/RepresentativeMenuConcurrencyIT.java`

**Interfaces:**
- Validates Tasks 2, 3, and 6 transaction behavior.

- [ ] **Step 1: Write concurrent RED tests**

Use barriers, not sleeps. Two replacements with `expectedVersion=0` must yield one success and one `COMMON_008`. Replacement racing HIDDEN must never leave a hidden selected menu; the final state must be either replacement rejected or committed then automatically removed.

- [ ] **Step 2: Verify RED/failure before final locking adjustments**

Run: `.\gradlew.bat integrationTest --tests com.miriyum.domain.menu.service.RepresentativeMenuConcurrencyIT --rerun-tasks`

Expected: initial test exposes missing serialization or fails until Task 6 lock order is complete.

- [ ] **Step 3: Make the smallest locking correction**

Keep the single order `setting row → sorted menu rows`; do not add JVM locks or sleeps. Translate stale version to `COMMON_008`; retry only database deadlocks if the repository's existing policy already supports it.

- [ ] **Step 4: Verify repeated GREEN and commit**

Run the focused concurrency test at least three fresh times with `--rerun-tasks`. Expected: PASS each time.

```powershell
git add -- backend/src/test/java/com/miriyum/domain/menu/service/RepresentativeMenuConcurrencyIT.java backend/src/main/java/com/miriyum/domain/menu/repository/RepresentativeMenuSettingRepository.java backend/src/main/java/com/miriyum/domain/menu/repository/MenuRepository.java backend/src/main/java/com/miriyum/domain/menu/service/RepresentativeMenuService.java backend/src/main/java/com/miriyum/domain/menu/service/MenuCommandService.java
git commit -m "test(store): 대표 메뉴 경합 검증"
```

### Task 8: 범위·전체 회귀와 #272 병합 게이트

**Files:**
- Modify only files already named in the exact allowlist if verification reveals a defect.

- [ ] **Step 1: Confirm migration order against latest dev**

Run: `git fetch origin dev:refs/remotes/origin/dev`

Run: `git ls-tree -r --name-only origin/dev backend/src/main/resources/db/migration | Select-String 'V3[4-7]__'`

Expected before merge: V36 exists on latest dev and #274 remains V37. If not, do not merge; update #274 and migration number before rebasing.

- [ ] **Step 2: Run complete verification**

Run: `.\gradlew.bat test --rerun-tasks`

Run: `.\gradlew.bat integrationTest --rerun-tasks`

Run: `.\gradlew.bat build --rerun-tasks`

Run Redocly lint/bundle commands from Task 1.

Run: `git diff --check origin/dev...HEAD`

Expected: all configured checks PASS; sandbox or unavailable external runtime is reported with its actual status, never inferred.

- [ ] **Step 3: Exact allowlist audit**

Run: `git diff --name-only origin/dev...HEAD`

Expected: every path is present in #274's stage 1 or stage 2 allowlist; no Payment, Reservation, MenuHold, frontend or unrelated file appears.

- [ ] **Step 4: Final review and commit only necessary fixes**

```powershell
git status --short
git diff --check
git commit -m "fix(store): 대표 메뉴 회귀 보완"
```

Create this final commit only if verification required an in-scope fix. Otherwise leave the verified commits unchanged.
