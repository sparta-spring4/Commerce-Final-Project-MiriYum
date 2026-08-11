# PR #228 Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the `store <-> menu` cycle introduced by PR #228, make the architecture tests fail closed, and record the approved PR2 Facade and Port/Adapter follow-up.

**Architecture:** PR1 consumers call the public `MenuTransactionService` directly so Store never imports Menu. `DomainPackageArchitectureTest` resolves real persistent types, rejects empty rule targets, and permits only the two cycles that predate PR1. PR2 later renames the proven common transaction coordinator to `MenuTransactionFacade` and removes both baseline cycles through controller ownership and dependency inversion.

**Tech Stack:** Java 21, Spring Boot, JUnit 5, AssertJ, Mockito, Gradle 9 wrapper

## Global Constraints

- PR1 must not change HTTP methods, URLs, JSON, errors, authentication, transactions, database schema, Flyway, or OpenAPI.
- Store must not import Menu after this remediation.
- Store locking must occur before Menu locking.
- PR1 may allow only `consumer <-> reservation` and `menuhold <-> reservation`; PR2 must reduce the allowed set to empty.
- Do not introduce the `MenuTransactionFacade` production class in PR1.

---

### Task 1: Fail-closed architecture rules

**Files:**
- Modify: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`

**Interfaces:**
- Consumes: Java sources below `src/main/java/com/miriyum/domain`
- Produces: `persistentTypeNames(List<SourceFile>)`, `domainDependencies(List<SourceFile>)`, and `cyclicDomainPairs(Map<String, Set<String>>)` test helpers

- [ ] **Step 1: Add failing assertions for uncovered persistence types, non-empty targets, and cycle baseline**

```java
private static final Set<DomainPair> ALLOWED_CYCLES = Set.of(
        new DomainPair("consumer", "reservation"),
        new DomainPair("menuhold", "reservation"));

@Test
void persistentTypesAreDetectedFromDeclarations() {
    assertThat(persistentTypeNames(javaSources()))
            .contains(
                    "com.miriyum.domain.auth.ratelimit.RateLimitWindow",
                    "com.miriyum.domain.auth.ratelimit.RateLimitWindowRepository",
                    "com.miriyum.domain.auth.logindelay.LoginFailureDelayRepository");
}

@Test
void domainsDoNotAddDependencyCycles() {
    assertThat(cyclicDomainPairs(domainDependencies(javaSources())))
            .containsExactlyInAnyOrderElementsOf(ALLOWED_CYCLES);
}
```

Change each filtered assertion to store its list and assert `isNotEmpty()` before `allMatch` or `noneMatch`.

- [ ] **Step 2: Run the architecture test and confirm RED**

Run: `backend/gradlew.bat test --tests com.miriyum.architecture.DomainPackageArchitectureTest --console=plain`

Expected: FAIL because `store <-> menu` is an additional cycle and the new declaration helpers do not exist until implemented.

- [ ] **Step 3: Implement declaration-based persistence and transitive cycle detection**

```java
private static Set<String> persistentTypeNames(List<SourceFile> sources) {
    return sources.stream()
            .filter(SourceFile::isPersistentType)
            .map(SourceFile::qualifiedName)
            .collect(Collectors.toUnmodifiableSet());
}

private boolean isPersistentType() {
    return packageName.contains(".entity")
            || simpleName.endsWith("Repository")
            || ENTITY_PATTERN.matcher(content).find();
}
```

Build the dependency graph from every `com.miriyum.domain.*` import. For every canonical domain pair, add the pair when each domain is transitively reachable from the other. Compare the exact result with `ALLOWED_CYCLES`.

- [ ] **Step 4: Run the architecture test and confirm the only remaining RED is `store <-> menu`**

Run: `backend/gradlew.bat test --tests com.miriyum.architecture.DomainPackageArchitectureTest --console=plain`

Expected: FAIL showing `DomainPair[first=menu, second=store]` in addition to the two allowed pairs.

### Task 2: Remove the Store-to-Menu wrapper

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuHoldServiceRuntime.java`
- Modify: `backend/src/main/java/com/miriyum/domain/menuhold/service/MenuInventoryAdminCommandService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/pickup/service/PickupReservationService.java`
- Modify: focused MenuHold, Pickup, Store unit and integration tests that construct or stub these services

**Interfaces:**
- Consumes: `MenuTransactionService.requireTransactionEligibility(long storeId, long menuId)`
- Produces: MenuHold, inventory administration, and Pickup transaction checks with no `StoreService.requireMenuTransactionEligibility` wrapper

- [ ] **Step 1: Change focused tests to use `MenuTransactionService` directly**

```java
@Mock MenuTransactionService menuTransactionService;

given(menuTransactionService.requireTransactionEligibility(STORE_ID, MENU_ID))
        .willReturn(eligibility);
```

Update constructor arguments and Spring `@MockitoBean`/`@MockitoSpyBean` declarations. Delete StoreService tests whose only behavior is forwarding to MenuTransactionService.

- [ ] **Step 2: Run focused tests and confirm RED**

Run: `backend/gradlew.bat test --tests '*MenuHoldCreateServiceTest' --tests '*MenuInventoryAdminCommandServiceTest' --tests '*PickupReservationServiceTest' --console=plain`

Expected: compilation FAIL because production constructors still require StoreService and the wrapper method still exists.

- [ ] **Step 3: Replace production dependencies and remove the wrapper**

```java
private final MenuTransactionService menuTransactionService;

MenuTransactionEligibility eligibility =
        menuTransactionService.requireTransactionEligibility(storeId, menuId);
```

Remove the Menu import, field, constructor dependency, and `requireMenuTransactionEligibility` method from `StoreService`.

- [ ] **Step 4: Run focused tests and the architecture test GREEN**

Run: `backend/gradlew.bat test --tests '*MenuHoldCreateServiceTest' --tests '*MenuInventoryAdminCommandServiceTest' --tests '*PickupReservationServiceTest' --tests com.miriyum.architecture.DomainPackageArchitectureTest --console=plain`

Expected: PASS with exactly the two PR1 baseline cycles.

### Task 3: Verification and GitHub review closure

**Files:**
- Modify only if verification exposes a regression in files already listed above

**Interfaces:**
- Consumes: complete PR branch and GitHub review thread IDs
- Produces: pushed commit(s), evidence-backed inline replies, and resolved review threads

- [ ] **Step 1: Run backend verification**

Run: `backend/gradlew.bat test --console=plain`

Expected: all backend tests PASS.

- [ ] **Step 2: Run repository checks**

Run: `git diff --check origin/dev...HEAD`

Expected: no output and exit code 0.

Run: `rg -n "com\.miriyum\.domain\.menu" backend/src/main/java/com/miriyum/domain/store`

Expected: no output.

- [ ] **Step 3: Commit and push the PR branch**

```text
git add <review-remediation-files>
git commit -m "fix: enforce domain dependency boundaries"
git push origin codex/issue-82-design
```

- [ ] **Step 4: Reply in each inline thread and resolve it**

The Store cycle reply must state that all consumers now call `MenuTransactionService` directly and the architecture test rejects any new cycle. The baseline reply must identify the two PR1 exceptions and the approved PR2 removal design. The persistence reply must state that declaration-based detection and non-empty guards were added. Include exact test evidence and reply to the inline comments rather than posting a top-level summary.
