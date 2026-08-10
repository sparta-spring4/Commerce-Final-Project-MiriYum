# Issue #82 Package Restructure Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Preserve every current HTTP, JSON, security, persistence, and business contract while splitting the store concentration into store, schedule, menu, and search domains and making Controller audience boundaries explicit.

**Architecture:** Keep the accepted `com.miriyum.domain.<domain>` three-layer structure. Package moves happen domain by domain; actor names appear only in Controller and HTTP DTO packages. New cross-domain access uses public Service/DTO contracts instead of another domain's Entity or Repository.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Gradle 9.6.1 Wrapper, JUnit 5, Mockito, Spring MockMvc, Testcontainers MySQL, OpenAPI YAML, React/TypeScript contract generation.

## Global Constraints

- Keep `Controller → Service → Repository`; do not add `application`, top-level `api`, ports/adapters, or formal wrapper layers.
- Do not change HTTP methods, URLs, request/response JSON, error codes, security policy, cookie behavior, DB schema, Flyway migrations, or business rules.
- Do not add compatibility endpoints; URL migration belongs only to PR2 after this PR is merged.
- Do not create empty or speculative packages.
- Keep `consumer` and `storeoperator` as physically separate account domains; divide their Controller/HTTP DTO boundaries by `auth` and `account` purpose.
- Use public Service/DTO contracts for cross-domain collaboration; never import another top-level domain's Entity or Repository.
- Preserve Java 21, Spring Boot 4.1.0, and Gradle 9.6.1.

---

### Task 1: Add package-architecture characterization tests

**Files:**
- Create: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- Test: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`

**Interfaces:**
- Consumes: production Java sources below `backend/src/main/java/com/miriyum/domain`.
- Produces: source-level rules `legacyStoreSubdomainsAreAbsent`, `controllersUseAudienceOrPurposePackage`, `controllersDoNotImportEntityOrRepository`, and `domainsDoNotImportForeignEntityOrRepository`.

- [ ] **Step 1: Write the failing architecture test**

Create a JUnit test that reads production `.java` files as UTF-8. It must:

```java
private static final Set<String> LEGACY_STORE_SUBDOMAINS = Set.of(
        "core", "schedule", "closure", "menu", "search", "recommendation");

@Test
void legacyStoreSubdomainsAreAbsent() {
    assertThat(javaSources())
            .noneMatch(source -> isBelowLegacyStoreSubdomain(source.path()));
}
```

The same class must identify a file's top-level domain from `package com.miriyum.domain.<name>` and reject imports matching `com.miriyum.domain.<other>...entity...` or `...repository...`. Controller sources must reject all Entity/Repository imports. Controller paths must end below one of `publicapi`, `consumer`, `storeoperator`, `auth`, or `account`, except infrastructure-free controllers outside the issue allowlist; encode the exact current Controller allowlist in the test so new unclassified controllers fail.

- [ ] **Step 2: Run the test and verify RED**

Run:

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.architecture.DomainPackageArchitectureTest
```

Expected: FAIL because `domain.store.core`, `domain.store.schedule`, `domain.store.closure`, `domain.store.menu`, `domain.store.search`, and `domain.store.recommendation` still exist and Controllers are not audience-partitioned.

- [ ] **Step 3: Commit the verified failing guard**

```powershell
git add -- backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java
git commit -m "test: define issue 82 package boundaries"
```

### Task 2: Promote Store core and catalog into the Store domain

**Files:**
- Move: `backend/src/main/java/com/miriyum/domain/store/core/**` into the matching `backend/src/main/java/com/miriyum/domain/store/**` layer directories.
- Move: `backend/src/test/java/com/miriyum/domain/store/core/**` into the matching `backend/src/test/java/com/miriyum/domain/store/**` directories.
- Move: `backend/src/main/java/com/miriyum/domain/store/controller/CatalogController.java` to `backend/src/main/java/com/miriyum/domain/store/controller/publicapi/CatalogController.java`.
- Move: `backend/src/main/java/com/miriyum/domain/store/core/controller/StoreController.java` to `backend/src/main/java/com/miriyum/domain/store/controller/storeoperator/StoreController.java`.
- Move matching Controller tests below `controller/publicapi` and `controller/storeoperator`.
- Move Store management HTTP DTOs to `backend/src/main/java/com/miriyum/domain/store/dto/storeoperator/**`.
- Modify: all Java imports referencing `com.miriyum.domain.store.core.*`.

**Interfaces:**
- Consumes: existing Store entities, repositories, policies, DTOs, and Controllers without semantic changes.
- Produces: `com.miriyum.domain.store.entity.Store`, `repository.StoreRepository`, `service.StoreService`, public catalog Controller, operator Store Controller, and operator HTTP DTOs.

- [ ] **Step 1: Move production and test files and rewrite package/import declarations mechanically**

Apply this exact namespace map:

```text
com.miriyum.domain.store.core.config      -> com.miriyum.domain.store.config
com.miriyum.domain.store.core.entity      -> com.miriyum.domain.store.entity
com.miriyum.domain.store.core.enums       -> com.miriyum.domain.store.enums
com.miriyum.domain.store.core.repository  -> com.miriyum.domain.store.repository
com.miriyum.domain.store.core.service     -> com.miriyum.domain.store.service
com.miriyum.domain.store.core.dto         -> com.miriyum.domain.store.dto.storeoperator
```

Put `CatalogController` in `controller.publicapi` and `StoreController` in `controller.storeoperator`. Preserve all annotations and route strings byte-for-byte.

- [ ] **Step 2: Compile and run Store-focused tests**

```powershell
cd backend
.\gradlew.bat compileJava
.\gradlew.bat test --tests "com.miriyum.domain.store.*"
```

Expected: compile and focused tests PASS; the full architecture test remains RED only for the still-unmoved domains.

- [ ] **Step 3: Commit the Store promotion**

```powershell
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor: promote store core package"
```

### Task 3: Promote Schedule and Closure into one Schedule domain

**Files:**
- Move: `backend/src/main/java/com/miriyum/domain/store/schedule/**` to `backend/src/main/java/com/miriyum/domain/schedule/**`.
- Move: `backend/src/main/java/com/miriyum/domain/store/closure/**` to `backend/src/main/java/com/miriyum/domain/schedule/closure/**`.
- Move: matching test trees from `domain/store/schedule` and `domain/store/closure`.
- Move: `StoreScheduleController` and `StoreClosureController` below their respective `controller/storeoperator` boundary.
- Move: schedule/closure HTTP request and response DTOs below `dto/storeoperator`; keep `StoreServiceIntervalRequest`, `StoreServiceIntervalResult`, `StoreServiceIntervalStatus`, and `StoreReservationWindow*` as public service DTOs under `dto/contract`.
- Modify: all Java imports referencing the old schedule/closure packages.

**Interfaces:**
- Consumes: Store public services `StoreService`, `StoreScheduleAuthority`, and `StoreScheduledActivationDecision`.
- Produces: top-level Schedule persistence/service layers, nested closure ownership, Store-operator HTTP boundary, and schedule contract DTOs used by Reservation/Search.

- [ ] **Step 1: Move Schedule/Closure sources and tests and rewrite namespaces**

Use `com.miriyum.domain.schedule` as the top-level domain. Closure remains `com.miriyum.domain.schedule.closure` so its internal model remains cohesive while architecture checks treat it as part of Schedule.

- [ ] **Step 2: Replace direct Store Entity/Repository use in interval validation**

Extend `StoreService` with a read-only batch contract returning a new immutable DTO:

```java
public record StoreServiceProfile(
        long storeId,
        String timeZoneId,
        boolean reservationAccepting
) {}

@Transactional(readOnly = true)
public Map<Long, StoreServiceProfile> getServiceProfiles(Set<Long> storeIds)
```

`StoreServiceIntervalValidationService` consumes this public contract and no longer imports `Store`, `StoreRepository`, `OperationStatus`, or `VerificationStatus`. Preserve missing-store and not-accepting behavior.

- [ ] **Step 3: Run Schedule/Closure unit and integration tests**

```powershell
cd backend
.\gradlew.bat test --tests "com.miriyum.domain.schedule.*" --tests "com.miriyum.domain.reservation.*"
.\gradlew.bat integrationTest --tests "com.miriyum.domain.schedule.*" --tests "com.miriyum.domain.reservation.*"
```

Expected: PASS with unchanged schedule publication, closure, and reservation interval decisions.

- [ ] **Step 4: Commit Schedule/Closure promotion**

```powershell
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor: promote schedule and closure domains"
```

### Task 4: Promote Menu and introduce its public transaction contract

**Files:**
- Move: `backend/src/main/java/com/miriyum/domain/store/menu/**` to `backend/src/main/java/com/miriyum/domain/menu/**`.
- Move: matching `backend/src/test/java/com/miriyum/domain/store/menu/**` tree.
- Move: `MenuController` and its test below `controller/storeoperator`.
- Move HTTP DTOs below `dto/storeoperator`; keep `MenuTransactionEligibility` and `MenuHoldSelectableMenu` under `dto/contract`.
- Create or modify: `backend/src/main/java/com/miriyum/domain/menu/service/MenuTransactionService.java`.
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`.
- Modify: all old Menu imports.

**Interfaces:**
- Consumes: `StoreService.requireStoreTransactionEligibility(storeId)` returning Store-owned eligibility facts.
- Produces: `MenuTransactionService.requireTransactionEligibility(storeId, menuId)` returning `MenuTransactionEligibility` without exposing Menu Entity/Repository.

- [ ] **Step 1: Add failing service tests for the new cross-domain contract**

Move the existing transaction-eligibility tests to the new packages, then add assertions proving StoreService does not resolve Menu persistence and MenuTransactionService combines Store facts with the locked published Menu version.

- [ ] **Step 2: Verify RED**

```powershell
cd backend
.\gradlew.bat test --tests "com.miriyum.domain.menu.service.*" --tests "com.miriyum.domain.store.service.StoreTransactionEligibilityServiceTest"
```

Expected: FAIL because `MenuTransactionService` and the Store-only transaction DTO do not exist yet.

- [ ] **Step 3: Move Menu and implement the minimal public Service/DTO split**

Store owns only Store state:

```java
public record StoreTransactionEligibility(
        long storeId,
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled
) {}
```

Menu owns Menu locking/version lookup and returns the existing transaction snapshot. Preserve lock order by calling Store eligibility before locking Menu.

- [ ] **Step 4: Run Menu, MenuHold, Pickup, and Store tests**

```powershell
cd backend
.\gradlew.bat test --tests "com.miriyum.domain.menu.*" --tests "com.miriyum.domain.menuhold.*" --tests "com.miriyum.domain.pickup.*" --tests "com.miriyum.domain.store.*"
.\gradlew.bat integrationTest --tests "com.miriyum.domain.menu.*" --tests "com.miriyum.domain.menuhold.*" --tests "com.miriyum.domain.pickup.*"
```

Expected: PASS with unchanged transaction eligibility, snapshot, and lock-order behavior.

- [ ] **Step 5: Commit Menu promotion**

```powershell
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor: promote menu domain"
```

### Task 5: Promote Search and move Schedule projection behind a public query Service

**Files:**
- Move: `backend/src/main/java/com/miriyum/domain/store/search/**` to `backend/src/main/java/com/miriyum/domain/search/**`.
- Move: `backend/src/main/java/com/miriyum/domain/store/recommendation/geo/**` to `backend/src/main/java/com/miriyum/domain/search/geo/**`.
- Move: matching test trees.
- Move: `StoreSearchController` and its test below `controller/publicapi`.
- Move public Search HTTP DTOs below `dto/publicapi`.
- Create: `backend/src/main/java/com/miriyum/domain/schedule/dto/contract/PublicStoreSchedules.java` and its daily/range contract records.
- Create: `backend/src/main/java/com/miriyum/domain/schedule/service/StoreScheduleQueryService.java`.
- Modify: `backend/src/main/java/com/miriyum/domain/search/service/StorePublicQueryService.java`.

**Interfaces:**
- Consumes: `StoreScheduleQueryService.getPublicSchedules(long storeId)`.
- Produces: `PublicStoreSchedules` containing immutable operating-hours and reservation-slot records; Search maps these records into its HTTP response DTOs.

- [ ] **Step 1: Add a failing Search service test using the Schedule query contract**

Update `StorePublicQueryServiceTest` so its constructor dependency is `StoreScheduleQueryService`, not Schedule repositories, and assert that an empty Schedule projection still produces empty arrays.

- [ ] **Step 2: Verify RED**

```powershell
cd backend
.\gradlew.bat test --tests "com.miriyum.domain.search.service.StorePublicQueryServiceTest"
```

Expected: FAIL because the new Schedule query Service/DTO contract is absent.

- [ ] **Step 3: Implement the Schedule projection contract and promote Search/geo packages**

Move the existing schedule loading/grouping logic without changing ordering or empty-state behavior. Search must no longer import Schedule Entity, Repository, or internal enum packages.

- [ ] **Step 4: Run Search and Schedule tests**

```powershell
cd backend
.\gradlew.bat test --tests "com.miriyum.domain.search.*" --tests "com.miriyum.domain.schedule.*"
.\gradlew.bat integrationTest --tests "com.miriyum.domain.search.*" --tests "com.miriyum.domain.schedule.*"
```

Expected: PASS with unchanged public store detail/menu/search responses.

- [ ] **Step 5: Commit Search promotion**

```powershell
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor: promote search domain"
```

### Task 6: Partition remaining HTTP boundaries by audience or account purpose

**Files:**
- Move Consumer Controllers/tests to `domain/consumer/controller/auth` and `domain/consumer/controller/account`.
- Move StoreOperator Controllers/tests to `domain/storeoperator/controller/auth` and `domain/storeoperator/controller/account`.
- Move MenuHold Controllers/tests to `domain/menuhold/controller/publicapi` and `domain/menuhold/controller/storeoperator`.
- Move Pickup Controllers/tests to `domain/pickup/controller/publicapi`, `domain/pickup/controller/consumer`, and `domain/pickup/controller/storeoperator`.
- Move Reservation Controllers/tests to `domain/reservation/controller/consumer` and `domain/reservation/controller/storeoperator`.
- Move purpose-specific or actor-specific HTTP DTO packages when the DTO is not a shared service contract.
- Modify: test imports, `@WebMvcTest` references, package-private test access, and any documentation that names Java packages.

**Interfaces:**
- Consumes: the same Services, principals, request DTOs, and response DTOs as before.
- Produces: explicit HTTP audience/purpose package ownership with unchanged annotations and URLs.

- [ ] **Step 1: Apply the exact Controller classification**

```text
consumer: ConsumerAuthController -> auth; ConsumerAccountController -> account
storeoperator: StoreOperatorAuthController -> auth; StoreOperatorAccountController -> account
menuhold: MenuHoldAvailabilityController -> publicapi; MenuInventoryAdminController -> storeoperator
pickup: PickupAvailabilityController -> publicapi; PickupReservationController -> consumer; PickupStoreManagementController -> storeoperator
reservation: ReservationController -> consumer; ReservationCapacityController, ReservationTimePolicyController, StoreReservationController -> storeoperator
```

Move matching tests to the same package. Preserve all mapping, validation, security principal, and response code declarations.

- [ ] **Step 2: Run Controller and Security tests**

```powershell
cd backend
.\gradlew.bat test --tests "*ControllerTest" --tests "com.miriyum.global.security.*" --tests "com.miriyum.domain.auth.*"
```

Expected: PASS with no URL or authorization behavior changes.

- [ ] **Step 3: Run and make the architecture guard GREEN**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.architecture.DomainPackageArchitectureTest
```

Expected: PASS; no legacy Store subdomain, unclassified Controller, Controller→Repository/Entity, or foreign Entity/Repository import remains.

- [ ] **Step 4: Commit actor boundary packages**

```powershell
git add -- backend/src/main/java backend/src/test/java
git commit -m "refactor: partition controllers by audience"
```

### Task 7: Align architecture documentation without changing HTTP contracts

**Files:**
- Modify: `backend/ai/implementation-guardrails.md`
- Modify: `docs/technical-architecture.md`
- Modify: `docs/specs/mvp1-common/ownership.md`
- Modify: package references found by `rg "domain\.store\.(core|schedule|closure|menu|search|recommendation)" backend docs`.
- Test: `docs/specs/mvp1-openapi.yaml` and feature OpenAPI files remain semantically unchanged.

**Interfaces:**
- Consumes: final production package map.
- Produces: documentation that names store, schedule, menu, and search as top-level domains and records audience-only Controller/HTTP DTO subdivisions.

- [ ] **Step 1: Update package/ownership documentation**

Document `publicapi` as unauthenticated public read HTTP boundary, not a partner API. State that Service/Repository/Entity remain domain-owned and are not actor-duplicated. Do not edit URL examples in PR1.

- [ ] **Step 2: Prove OpenAPI and generated frontend contracts did not drift**

```powershell
cd frontend
pnpm run generate:api
git diff --exit-code -- src/shared/api/generated
pnpm run typecheck
pnpm test
pnpm run build
```

Expected: generated API diff is empty; typecheck, tests, and build PASS.

- [ ] **Step 3: Commit documentation alignment**

```powershell
git add -- backend/ai/implementation-guardrails.md docs/technical-architecture.md docs/specs/mvp1-common/ownership.md docs
git commit -m "docs: align issue 82 domain ownership"
```

### Task 8: Full verification, focused review, and PR publication

**Files:**
- Review: `origin/dev...HEAD`
- No new production files unless a failing verification receives a test-first fix.

**Interfaces:**
- Consumes: Tasks 1–7.
- Produces: a reviewed PR1 targeting `dev`, linked to Issue #82 but not closing it because PR2 remains.

- [ ] **Step 1: Run fresh full verification**

```powershell
git diff --check origin/dev...HEAD
cd backend
.\gradlew.bat clean test integrationTestShardA integrationTestShardB
cd ..\frontend
pnpm run generate:api
git diff --exit-code -- src/shared/api/generated
pnpm run typecheck
pnpm test
pnpm run build
```

Expected: every command exits 0. If local Docker is unavailable, record the exact blocked integration commands and require exact-head GitHub integration checks before marking the PR ready.

- [ ] **Step 2: Review the complete diff against the design**

Check every design completion criterion, search for old package names, confirm no route mapping/string changed, inspect Security and cookie diffs for accidental changes, confirm no Flyway files changed, and verify the worktree is clean.

- [ ] **Step 3: Push the branch and create a draft PR**

```powershell
git push -u origin codex/issue-82-design
gh pr create --draft --base dev --head codex/issue-82-design --title "refactor: reorganize domain packages for #82" --body-file <temporary-markdown-file>
```

The PR body must state that this is PR1/2, list the package and cross-domain contract changes, state that URLs/OpenAPI/DB are unchanged, include exact verification results, link Issue #82 without `Closes`, and say PR2 will migrate routes after merge.
