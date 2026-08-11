# Menu Alternative Search Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 품절 또는 마지막 수량 경합 실패 뒤 같은 매장 우선, 없을 때 원 매장 저장 좌표 3km 이내의 예약·수량 가능한 메뉴 대안을 결정적으로 조회하는 공개 API를 구현한다.

**Architecture:** 최신 #82 경계에 맞춰 Search 도메인이 Store/Menu 테이블의 공개 읽기 모델을 `MenuAlternativeCandidateQueryService`와 immutable DTO로 제공하고, 새 최상위 Recommendation 도메인이 그 계약과 Reservation·MenuHold 공개 계약을 조합한다. 복합 조건과 알레르기 코드는 POST JSON body로 받고, 같은 매장 적격 후보가 하나라도 있으면 다른 매장 조회를 수행하지 않는다.

**Tech Stack:** Java 21, Spring Boot, Spring MVC, Spring Security, QueryDSL JPA, MySQL, Testcontainers, JUnit 5, AssertJ, Mockito, MockMvc, OpenAPI, pnpm/TypeScript 생성 타입

## Global Constraints

- 대상 단계와 브랜치는 `2차 MVP`, `mvp2`다.
- 구현 전에 최신 `dev`를 별도 sync 브랜치로 `mvp2`에 반영하고 PR #222 계약을 확인한다.
- 다른 도메인의 Entity·Repository를 직접 참조하지 않고 Reservation·MenuHold 공개 Service·DTO만 사용한다.
- 사용자 현재 위치를 요청·저장·URL·로그에 남기지 않는다.
- 추천 요청 중 Kakao Local API 또는 Kakao Maps API를 호출하지 않는다.
- AI/LLM, Spring AI, 벡터 DB, 검색 엔진, 추천 캐시, Kafka, 범용 Outbox를 도입하지 않는다.
- 알레르기 조건과 전체 request body를 로그에 남기지 않는다.
- 새 오류 코드를 만들지 않고 기존 Store·Reservation·MenuHold·공통 오류 의미를 보존한다.
- 모든 production 동작은 실패 테스트를 먼저 실행한 뒤 최소 구현으로 통과시킨다.
- Issue #114의 실제 production/test/docs/OpenAPI allowlist를 동기화 후 확정하고 그 밖의 파일은 stage하지 않는다.
- PR은 `dev → mvp2` sync, Search contract-first, Recommendation feature의 세 stacked 경계로 분리한다.

---

### Task 1: 최신 dev를 mvp2에 격리 동기화하고 stacked base 확정

**Files:**
- Merge review: `backend/build.gradle.kts`
- Merge review: `docs/service-policies/02-store-onboarding.md`
- Merge review: `docs/specs/store-search/spec.md`
- Move after merge: `backend/src/main/java/com/miriyum/domain/store/recommendation/ranking/**` → `backend/src/main/java/com/miriyum/domain/recommendation/ranking/**`
- Move after merge: `backend/src/test/java/com/miriyum/domain/store/recommendation/ranking/**` → `backend/src/test/java/com/miriyum/domain/recommendation/ranking/**`
- Modify after merge: `backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java`

**Interfaces:**
- Consumes: `origin/mvp2`, `origin/dev`, PR #222 merge commit `28f4955eee0935d8bae3d6816e47add9c78c7408`
- Produces: `codex/sync-dev-into-mvp2-20260811` branch with #113 ranking relocated to the #82-approved top-level Recommendation domain, and a sync PR targeting `mvp2`

- [ ] **Step 1: Fetch and create the isolated sync branch**

```powershell
git fetch origin dev:refs/remotes/origin/dev mvp2:refs/remotes/origin/mvp2
git worktree add .superpowers/sdd/worktrees/sync-dev-into-mvp2-20260811 `
  -b codex/sync-dev-into-mvp2-20260811 origin/mvp2
```

- [ ] **Step 2: Merge dev without rewriting either branch**

```powershell
git merge --no-ff origin/dev -m "merge: sync latest dev into mvp2"
```

Expected: merge completes without unresolved files. If Git reports a conflict, stop before editing and re-map the conflicting ownership boundaries.

- [ ] **Step 3: Relocate the mvp2-only ranking package required by #82**

Move the complete ranking production and test directories from `com.miriyum.domain.store.recommendation.ranking` to `com.miriyum.domain.recommendation.ranking`, change package declarations, and update the merged `IntegratedStoreSearchService` imports. Do not leave both package trees.

- [ ] **Step 4: Verify the required public contracts survived the merge**

```powershell
git merge-base --is-ancestor 28f4955eee0935d8bae3d6816e47add9c78c7408 HEAD
git grep -n "findExistingOnlineAvailability" -- backend/src/main/java
git grep -n "resolveReservationTimes\|getAvailabilities" -- backend/src/main/java/com/miriyum/domain/reservation
git grep -n "StoreDistanceEligibility" -- backend/src/main/java/com/miriyum/domain/search/geo
git grep -n "package com.miriyum.domain.store.recommendation" -- backend/src/main/java backend/src/test/java
```

Expected: ancestor command exits 0, all three public capabilities are present, and the obsolete package grep returns no matches.

- [ ] **Step 5: Run the synchronized baseline**

```powershell
Set-Location backend
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon integrationTestShardA
.\gradlew.bat --no-daemon integrationTestShardB
.\gradlew.bat --no-daemon build
Set-Location ..\frontend
pnpm.cmd run typecheck
pnpm.cmd run test
pnpm.cmd run build
Set-Location ..
git diff --check
```

Expected: every command exits 0. A baseline failure is investigated before #114 code begins.

- [ ] **Step 6: Commit, push, and open the sync PR**

```powershell
git add backend/src/main/java/com/miriyum/domain/recommendation/ranking `
  backend/src/test/java/com/miriyum/domain/recommendation/ranking `
  backend/src/main/java/com/miriyum/domain/search/service/IntegratedStoreSearchService.java
git commit -m "refactor(recommendation): mvp2 추천 정렬 패키지 승격"
git push -u origin codex/sync-dev-into-mvp2-20260811
gh pr create --base mvp2 --head codex/sync-dev-into-mvp2-20260811 `
  --title "merge(store): 최신 dev를 mvp2에 동기화" `
  --body "PR #222 공개 계약과 최신 dev 변경을 2차 MVP 기준 브랜치에 반영합니다."
```

- [ ] **Step 7: Bring the sync commit into the feature branch**

```powershell
git merge --no-ff codex/sync-dev-into-mvp2-20260811 `
  -m "merge: base issue 114 on latest dev sync"
```

Expected: `codex/114-menu-alternatives` contains the sync branch and its own design commit. The eventual feature PR targets the sync branch until the sync PR is merged.

---

### Task 2: Add the Search-owned contract-first candidate query

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/search/dto/contract/MenuAlternativeSourceView.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/dto/contract/MenuAlternativeCandidateView.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/dto/contract/MenuAlternativeAllergenView.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/service/MenuAlternativeCandidateQueryService.java`
- Create: `backend/src/main/java/com/miriyum/domain/search/repository/MenuAlternativeCandidateRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/search/contract/MenuAlternativeCandidatePublicContractTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/search/repository/MenuAlternativeCandidateRepositoryIT.java`

**Interfaces:**
- Consumes: Search-owned MySQL read model over current Store/Menu publication, modes, allergen disclosures, and verified coordinates
- Produces:
  - `MenuAlternativeSourceView findSource(long storeId, long menuId)`
  - `List<MenuAlternativeCandidateView> findSameStoreCandidates(MenuAlternativeSourceView source)`
  - `List<MenuAlternativeCandidateView> findNearbyCandidates(MenuAlternativeSourceView source, BoundingBox box, int limit)`

- [ ] **Step 1: Create the stacked contract branch and re-gate Issue #114**

```powershell
git worktree add .superpowers/sdd/worktrees/issue-114-alternative-candidate-contract `
  -b codex/114-alternative-candidate-contract codex/sync-dev-into-mvp2-20260811
```

Add the exact Task 2 paths to Issue #114 and state that Recommendation may consume only these immutable DTOs and service methods, never Store/Menu Entity or Repository types.

- [ ] **Step 2: Write RED public contract and MySQL projection tests**

The public contract test rejects Entity, Repository, JPA annotation, and mutable collection exposure. The MySQL test covers approved/open Store state, reservation/menu-hold modes, current published visible/selling menu version, primary/secondary categories, allergen registration/disclosures, verified coordinates, source exclusion, bounding-box filtering, stable `storeId/menuId` order, and the supplied hard limit.

- [ ] **Step 3: Run and verify RED**

```powershell
Set-Location backend
.\gradlew.bat test --tests "*MenuAlternativeCandidatePublicContractTest"
.\gradlew.bat integrationTestShardA --tests "*MenuAlternativeCandidateRepositoryIT"
```

Expected: compilation/test failure because the public contract is absent.

- [ ] **Step 4: Implement the minimal Search read contract**

Use QueryDSL scalar projections and bounded batch collection reads keyed by published `menuVersionId`. Return defensive immutable lists and central allergen code/status names without exposing `com.miriyum.domain.menu.entity`, `repository`, or mutable model types. Queries are read-only and non-locking.

- [ ] **Step 5: Run GREEN, architecture tests, and commit**

```powershell
.\gradlew.bat test --tests "*MenuAlternativeCandidatePublicContractTest" --tests "*DomainPackageArchitectureTest"
.\gradlew.bat integrationTestShardA --tests "*MenuAlternativeCandidateRepositoryIT"
git add backend/src/main/java/com/miriyum/domain/search `
  backend/src/test/java/com/miriyum/domain/search
git commit -m "feat(search): 메뉴 대안 후보 공개 조회 계약 추가"
```

- [ ] **Step 6: Push and open the contract-first PR**

```powershell
git push -u origin codex/114-alternative-candidate-contract
gh pr create --base codex/sync-dev-into-mvp2-20260811 `
  --head codex/114-alternative-candidate-contract `
  --title "feat(search): 메뉴 대안 후보 조회 계약 추가" `
  --body "Issue #114 Recommendation 구현이 소비할 Search 소유 read-only Service/DTO 계약입니다."
```

- [ ] **Step 7: Merge the local contract branch into the feature branch**

```powershell
git merge --no-ff codex/114-alternative-candidate-contract `
  -m "merge: consume issue 114 search candidate contract"
```

The feature PR must target `codex/114-alternative-candidate-contract` until the contract PR merges.

---

### Task 3: Implement deterministic menu eligibility and ordering

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/model/AlternativeMenuSource.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/model/AlternativeMenuCandidate.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/model/AlternativeReasonCode.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeEligibility.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeOrdering.java`
- Test: `backend/src/test/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeEligibilityTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeOrderingTest.java`

**Interfaces:**
- Consumes: Search contract DTO scalar allergen code, disclosure status, and registration status values from Task 2
- Produces:
  - `EligibilityResult evaluate(AlternativeMenuSource source, AlternativeMenuCandidate candidate, Set<AllergenIngredientCode> excluded)`
  - `Comparator<EligibleAlternative> sameStoreComparator()`
  - `Comparator<EligibleAlternative> nearbyStoreComparator()`

- [ ] **Step 1: Write RED tests for category, price, and allergen rules**

Cover these independent examples:

```java
@Test void includesPricesAtExactlyEightyAndOneHundredTwentyPercent() { }
@Test void excludesDifferentPrimaryCategory() { }
@Test void countsDistinctSecondaryCategoryIntersection() { }
@Test void excludesUnregisteredAllergenInformationWhenExclusionsExist() { }
@Test void excludesContainsAndMayContainForRequestedCodes() { }
@Test void allowsRegisteredCandidateWithoutRequestedRiskCode() { }
```

The 80% and 120% calculation uses integer inequalities (`candidatePrice * 100` against `sourcePrice * 80/120`) with `long` intermediates so integer division cannot move the boundary.

- [ ] **Step 2: Run and verify RED**

```powershell
Set-Location backend
.\gradlew.bat test --tests "*MenuAlternativeEligibilityTest" --tests "*MenuAlternativeOrderingTest"
```

Expected: compilation/test failure because the new policy API is absent.

- [ ] **Step 3: Implement the minimal immutable model and eligibility policy**

The policy returns an eligible value containing `secondaryCategoryMatchCount`, absolute price difference, and reason codes. It never reads Spring, JPA, Reservation, MenuHold, coordinates, or the clock.

- [ ] **Step 4: Add RED ordering tests**

```java
@Test void sameStoreOrdersBySecondaryMatchesPriceDifferencePriceAndMenuId() { }
@Test void nearbyOrdersByDistanceSecondaryMatchesPriceDifferenceStoreAndMenuId() { }
```

- [ ] **Step 5: Implement comparators and verify GREEN**

```powershell
.\gradlew.bat test --tests "*MenuAlternativeEligibilityTest" --tests "*MenuAlternativeOrderingTest"
```

Expected: PASS.

- [ ] **Step 6: Commit the pure policy**

```powershell
git add backend/src/main/java/com/miriyum/domain/recommendation/alternative `
  backend/src/test/java/com/miriyum/domain/recommendation/alternative
git commit -m "feat(store): 대안 메뉴 적격성과 정렬 규칙 추가"
```

---

### Task 4: Lock the canonical POST search contract

**Files:**
- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/store-search/openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/search/controller/publicapi/StoreSearchOpenApiContractTest.java`
- Modify generated: `frontend/src/shared/api/generated/store-search.ts`

**Interfaces:**
- Consumes: approved design request/response and Task 2 Search candidate contract
- Produces: `POST /api/v1/stores/{storeId}/menus/{menuId}/alternatives/search`, request/response schemas, `AlternativeMode`, and `AlternativeReasonCode`

- [ ] **Step 1: Write the failing OpenAPI assertion**

Assert that the exact path has only a POST operation with JSON `requestBody`; required fields are `quantity`, `serviceDate`, `startTime`, and `partySize`; enum modes are `SAME_STORE`, `NEARBY_STORE`, `NO_ALTERNATIVE`, `REGION_SELECTION_REQUIRED`; and allergen codes use the existing central enum values.

- [ ] **Step 2: Run and verify RED**

```powershell
Set-Location backend
.\gradlew.bat test --tests "*StoreSearchOpenApiContractTest"
```

Expected: FAIL because the path and schemas do not exist.

- [ ] **Step 3: Add spec and OpenAPI definitions**

The body constraints are `quantity >= 1`, `partySize 1..100`, minute-precision `startTime`, optional exact `±HH:MM` offset, default `includesInfants=false`, unique optional allergen codes, and `size 1..20` default 10. The response uses string PublicIds, offset timestamps, IANA zone, nullable distance/coordinates, and one of the four modes.

- [ ] **Step 4: Regenerate the frontend contract and verify GREEN**

```powershell
Set-Location ..\frontend
pnpm.cmd run generate:api
Set-Location ..\backend
.\gradlew.bat test --tests "*StoreSearchOpenApiContractTest"
```

Expected: PASS and only `frontend/src/shared/api/generated/store-search.ts` changes among generated files.

- [ ] **Step 5: Commit the canonical contract**

```powershell
git add docs/specs/store-search/spec.md docs/specs/store-search/openapi.yaml `
  backend/src/test/java/com/miriyum/domain/search/controller/publicapi/StoreSearchOpenApiContractTest.java `
  frontend/src/shared/api/generated/store-search.ts
git commit -m "docs(recommendation): 품절 대안 검색 계약 추가"
```

---

### Task 5: Implement same-store-first orchestration

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/model/MenuAlternativeMode.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/model/MenuAlternativeResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/model/ResolvedAlternativeItem.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeSearchService.java`
- Test: `backend/src/test/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeSearchServiceTest.java`

**Interfaces:**
- Consumes:
  - `ReservationService.resolveReservationTimes(List<Long>, ReservationTimeRequest)`
  - `MenuInventoryTransactionService.findExistingOnlineAvailability(MenuInventoryAvailabilityQuery)`
  - `MenuAlternativeCandidateQueryService` and its Task 2 immutable DTOs
  - Tasks 3–4 policy and repository
- Produces: `MenuAlternativeResult search(long storeId, long menuId, MenuAlternativeSearchCommand command)`

- [ ] **Step 1: Write RED tests for source interval and same-store priority**

```java
@Test void returnsOnlySameStoreAlternativesWhenAnyCandidateHasRequestedQuantity() { }
@Test void skipsInventoryCallWhenStoreCandidateListIsEmpty() { }
@Test void excludesMissingBucketSoldOutAndInsufficientQuantity() { }
@Test void failsClosedWhenReservationTimeIdentityOrIntervalDoesNotMatch() { }
```

The first test verifies that nearby repository and nearby Reservation calls are never invoked after a same-store result survives.

- [ ] **Step 2: Run and verify RED**

```powershell
Set-Location backend
.\gradlew.bat test --tests "*MenuAlternativeSearchServiceTest"
```

Expected: FAIL because orchestration is absent.

- [ ] **Step 3: Implement only source resolution and same-store path**

Construct `ReservationTimeRequest(serviceDate, startTime, startOffset)`, require exactly one matching `RESOLVED` source result, convert the offset/IANA result into MenuHold `serviceDate/startTime/endDate/endTime`, and call `findExistingOnlineAvailability` once with sorted distinct candidate menu IDs.

- [ ] **Step 4: Verify GREEN for same-store tests**

```powershell
.\gradlew.bat test --tests "*MenuAlternativeSearchServiceTest"
```

Expected: same-store cases PASS; nearby cases are not added yet.

- [ ] **Step 5: Commit the same-store vertical slice**

```powershell
git add backend/src/main/java/com/miriyum/domain/recommendation/alternative `
  backend/src/test/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeSearchServiceTest.java
git commit -m "feat(store): 같은 매장 메뉴 대안 조회 추가"
```

---

### Task 6: Implement coordinate fallback and nearby-store revalidation

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeSearchService.java`
- Test: `backend/src/test/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeSearchServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeSearchServiceIT.java`

**Interfaces:**
- Consumes:
  - `ReservationService.getAvailabilities(List<Long>, ReservationAvailabilityCondition)`
  - `ReservationService.resolveReservationTimes(List<Long>, ReservationTimeRequest)`
  - `BoundingBoxCalculator`, `HaversineDistanceCalculator`, `StoreDistanceEligibility`
  - `MenuInventoryTransactionService.findExistingOnlineAvailability(...)`
- Produces: `NEARBY_STORE`, `NO_ALTERNATIVE`, and `REGION_SELECTION_REQUIRED` results

- [ ] **Step 1: Add RED fallback and 3km boundary tests**

```java
@Test void returnsRegionSelectionRequiredWithoutVerifiedSourceCoordinates() { }
@Test void includesCandidateAtExactlyThreeKilometers() { }
@Test void excludesCandidateBeyondThreeKilometers() { }
```

- [ ] **Step 2: Run and verify RED**

```powershell
Set-Location backend
.\gradlew.bat test --tests "*MenuAlternativeSearchServiceTest"
```

Expected: FAIL because nearby path is absent.

- [ ] **Step 3: Implement coordinate and bounded candidate filtering**

Create the 3,000m bounding box from the stored source coordinate, request at most `StoreSearchCandidateLimit.value()` rows, and apply Haversine final eligibility without rounding.

- [ ] **Step 4: Add RED Reservation/MenuHold composition tests**

Cover:

```java
@Test void excludesStoresThatCannotAcceptPartySizeAndInfantCondition() { }
@Test void resolvesTimesOnlyForReservationAvailableStores() { }
@Test void groupsMenuInventoryQueriesByExactResolvedServiceInterval() { }
@Test void failsClosedOnAvailabilityOrTimeResolutionIdentityMismatch() { }
@Test void returnsNoAlternativeWhenAllNearbyMenusFailInventory() { }
```

- [ ] **Step 5: Implement nearby revalidation and deterministic ordering**

Call `getAvailabilities` once for distinct nearby store IDs, retain only `AVAILABLE`, then call `resolveReservationTimes` once for those IDs. Group candidate menu IDs by exact resolved inventory interval before `findExistingOnlineAvailability`; validate returned menu IDs, interval, time zone, uniqueness, and order before joining results.

- [ ] **Step 6: Add a real MySQL integration slice**

The integration test creates a source store, an ineligible same-store candidate, one exact-3km eligible nearby candidate with an actual inventory bucket, and one over-3km candidate. Assert only the exact-boundary candidate is returned and no user-coordinate field or external adapter call exists.

- [ ] **Step 7: Run focused unit and integration GREEN**

```powershell
.\gradlew.bat test --tests "*MenuAlternativeSearchServiceTest"
.\gradlew.bat integrationTestShardA --tests "*MenuAlternativeSearchServiceIT"
```

Expected: PASS.

- [ ] **Step 8: Commit the nearby-store slice**

```powershell
git add backend/src/main/java/com/miriyum/domain/recommendation/alternative/service/MenuAlternativeSearchService.java `
  backend/src/test/java/com/miriyum/domain/recommendation/alternative/service
git commit -m "feat(store): 3km 인근 매장 대안 조회 추가"
```

---

### Task 7: Expose the public POST endpoint and reuse security/rate limiting

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/controller/publicapi/MenuAlternativeSearchController.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/dto/publicapi/MenuAlternativeSearchRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/recommendation/alternative/dto/publicapi/MenuAlternativeSearchResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/config/StoreSearchSecurityConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/search/config/StoreSearchRateLimitFilter.java`
- Test: `backend/src/test/java/com/miriyum/domain/recommendation/alternative/controller/publicapi/MenuAlternativeSearchControllerTest.java`
- Modify test: `backend/src/test/java/com/miriyum/domain/search/config/StoreSearchSecurityConfigTest.java`
- Modify test: `backend/src/test/java/com/miriyum/domain/search/config/StoreSearchRateLimitIT.java`

**Interfaces:**
- Consumes: `MenuAlternativeSearchService.search(...)`
- Produces: validated public POST endpoint and common response envelope

- [ ] **Step 1: Write RED MockMvc request and response tests**

Cover successful `SAME_STORE`, empty `NO_ALTERNATIVE`, `REGION_SELECTION_REQUIRED`, invalid quantity, invalid party size, second-precision time, invalid offset, unknown allergen code, size 0/21, unknown JSON property, and string PublicId parsing.

- [ ] **Step 2: Verify RED**

```powershell
Set-Location backend
.\gradlew.bat test --tests "*MenuAlternativeSearchControllerTest"
```

Expected: FAIL because route and DTOs do not exist.

- [ ] **Step 3: Implement request parsing and response mapping**

Use Bean Validation for scalar ranges, preserve the raw `startOffset` string long enough to enforce exact `±HH:MM`, convert to `ZoneOffset` only after regex validation, copy/deduplicate allergen codes, and default `includesInfants=false`, `size=10`. The controller delegates once and performs no candidate calculations.

- [ ] **Step 4: Write RED public security and rate-limit tests**

Assert anonymous POST is permitted only at the exact alternatives search path, other POSTs under `/api/v1/stores/**` remain denied, and requests consume `RateLimitCategory.PUBLIC_STORE_READ` before reaching the controller.

- [ ] **Step 5: Implement exact security/rate-limit path matching**

Add only:

```java
.requestMatchers(HttpMethod.POST,
        "/api/v1/stores/{storeId}/menus/{menuId}/alternatives/search")
.permitAll()
```

and an anchored regex matching the same POST route. Do not broaden the existing wildcard or create a new filter chain/category.

- [ ] **Step 6: Run controller, security, and rate-limit GREEN**

```powershell
.\gradlew.bat test --tests "*MenuAlternativeSearchControllerTest" `
  --tests "*StoreSearchSecurityConfigTest"
.\gradlew.bat integrationTestShardB --tests "*StoreSearchRateLimitIT"
```

Expected: PASS.

- [ ] **Step 7: Commit the HTTP slice**

```powershell
git add backend/src/main/java/com/miriyum/domain/recommendation/alternative/controller `
  backend/src/main/java/com/miriyum/domain/recommendation/alternative/dto `
  backend/src/main/java/com/miriyum/domain/search/config `
  backend/src/test/java/com/miriyum/domain/recommendation/alternative/controller `
  backend/src/test/java/com/miriyum/domain/search/config
git commit -m "feat(store): 공개 메뉴 대안 검색 API 추가"
```

---

### Task 8: Full verification, issue evidence, push, and stacked PR

**Files:**
- Verify all Issue #114 allowlisted files
- No new production behavior in this task

**Interfaces:**
- Consumes: Tasks 1–7
- Produces: verified branch and GitHub Pull Request

- [ ] **Step 1: Run focused regression**

```powershell
Set-Location backend
.\gradlew.bat test --tests "*MenuAlternative*" --tests "*StoreSearchSecurityConfigTest"
.\gradlew.bat integrationTestShardA --tests "*MenuAlternative*"
.\gradlew.bat integrationTestShardB --tests "*StoreSearchRateLimitIT"
```

Expected: PASS.

- [ ] **Step 2: Run complete backend verification**

```powershell
.\gradlew.bat --no-daemon test
.\gradlew.bat --no-daemon integrationTestShardA
.\gradlew.bat --no-daemon integrationTestShardB
.\gradlew.bat --no-daemon build
```

Expected: PASS with no failed tests.

- [ ] **Step 3: Run complete frontend generated-contract verification**

```powershell
Set-Location ..\frontend
pnpm.cmd run generate:api
git diff --exit-code -- src/shared/api/generated/store-search.ts
pnpm.cmd run typecheck
pnpm.cmd run test
pnpm.cmd run build
```

Expected: generation is stable and every command exits 0.

- [ ] **Step 4: Run repository and privacy scope checks**

```powershell
Set-Location ..
git diff --check
git diff --name-only codex/114-alternative-candidate-contract...HEAD
git grep -n "currentLocation\|geolocation\|navigator.geolocation" -- `
  backend/src/main/java/com/miriyum/domain/recommendation/alternative
git grep -n "KakaoLocal\|KakaoMap\|OpenAI\|SpringAi" -- `
  backend/src/main/java/com/miriyum/domain/recommendation/alternative
git status --short
```

Expected: changed paths equal Issue #114 allowlist; privacy/external-technology greps return no matches; worktree is clean after committed generated output.

- [ ] **Step 5: Update Issue #114 with executed evidence**

Record exact commands, exit codes, focused/full test counts, unrun checks, residual risks, and the fact that results are observational and do not guarantee acquisition.

- [ ] **Step 6: Push the feature branch**

```powershell
git push -u origin codex/114-menu-alternatives
```

- [ ] **Step 7: Open the feature PR against the sync branch**

```powershell
gh pr create --base codex/114-alternative-candidate-contract `
  --head codex/114-menu-alternatives `
  --title "feat(store): 품절 메뉴 대안 검색 구현" `
  --body-file .superpowers/sdd/issue-114-pr-body.md
```

The PR body must include `Closes #114`, acceptance-criterion mapping, actual validation evidence, residual risks, rollback, requested Search/MenuHold/Reservation reviewers, and a note to retarget to `mvp2` after both prerequisite PRs merge.

- [ ] **Step 8: Verify remote CI and report remaining review gates**

```powershell
$featurePr = gh pr view codex/114-menu-alternatives --json number --jq .number
gh pr checks $featurePr --watch
```

Expected: required checks pass. Do not claim merge readiness until required human approvals and the sync base merge/retarget are complete.
