# Menu Transaction Eligibility Contract Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Store-owned service contract that validates a menu's Store ownership and current transaction state, then returns the current published version and final channel capabilities for Issue #42.

**Architecture:** `StoreService` acquires Store then Menu pessimistic locks, applies Store state and ownership checks, delegates Menu lifecycle interpretation to the `Menu` aggregate, and assembles a state-free `MenuTransactionEligibility` record. Consumers receive final hold/pickup capability booleans and never access Store/Menu entities, repositories, or lifecycle enums.

**Tech Stack:** Java 21, Spring Boot 4, Spring Data JPA, JUnit 5, Mockito, AssertJ, Gradle

## Global Constraints

- Work only on `codex/85-menu-transaction-eligibility`, based on the latest `origin/dev`.
- Production scope is limited to `StoreService.java`, `Menu.java`, and new `MenuTransactionEligibility.java`.
- Contract tests belong in `StoreServiceTest.java`; do not modify `domain/menuhold/**`.
- Do not add a Controller, HTTP route, migration, repository method, or error code.
- Missing Store uses `STORE_001`; non-approved Store uses `STORE_007`.
- Any Store operation status other than `OPEN` uses `STORE_005` for a new transaction.
- Missing Menu or wrong Store ownership uses `STORE_009` without disclosing the actual owner.
- Retired, unpublished, hidden, paused, or sold-out Menu uses `STORE_010`.
- Locks are acquired in Store-then-Menu order.
- The DTO exposes final capabilities, not Store/Menu state enums or entities.

---

## File Structure

- `backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuTransactionEligibility.java`
  - immutable public cross-domain result with IDs, published version number, and final capabilities.
- `backend/src/main/java/com/miriyum/domain/store/menu/entity/Menu.java`
  - owns lifecycle/exposure validation and returns the current transaction `MenuVersion` only when valid.
- `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
  - owns Store validation, Store/Menu lock order, ownership hiding, and capability composition.
- `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`
  - verifies the complete public contract without involving Issue #42.

### Task 1: Implement the Store-Owned Eligibility Contract

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuTransactionEligibility.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/menu/entity/Menu.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`

**Interfaces:**
- Consumes: `StoreRepository.findByIdForUpdate(long)` and `MenuRepository.findByIdForUpdate(long)`.
- Produces: `Menu.requireTransactionVersion()` returning `MenuVersion` or throwing `STORE_010`.
- Produces: `StoreService.requireMenuTransactionEligibility(long storeId, long menuId)` returning `MenuTransactionEligibility`.
- Produces: `MenuTransactionEligibility(long storeId, long menuId, int publishedVersionNumber, boolean menuHoldEligible, boolean pickupEligible)`.

- [ ] **Step 1: Add the Menu repository dependency and red contract fixtures**

Add a Mockito field and pass it to the explicit constructor in `StoreServiceTest`:

```java
@Mock
private MenuRepository menuRepository;

storeService = new StoreService(
        operatorAccountService,
        storeRepository,
        menuRepository,
        catalogPolicy,
        idempotencyExecutor,
        objectMapper,
        FIXED_CLOCK);
```

Add a `MENU_ID` constant and a published Menu fixture built through public domain methods:

```java
private static final long MENU_ID = 21L;

private Menu menu(long storeId, boolean holdAllowed, boolean pickupAllowed) {
    Menu menu = Menu.create(
            storeId,
            menuContent(holdAllowed, pickupAllowed),
            OPERATOR_ID,
            FIXED_CLOCK.instant());
    ReflectionTestUtils.setField(menu, "id", MENU_ID);
    return menu;
}

private MenuContent menuContent(boolean holdAllowed, boolean pickupAllowed) {
    return new MenuContent(
            "아메리카노", "설명", 5_000, false, "COFFEE",
            List.of(), List.of(), holdAllowed, pickupAllowed,
            DisclosureRegistrationStatus.REGISTERED,
            List.of(new AllergenDisclosure(
                    AllergenIngredientCode.MILK,
                    AllergenDisclosureStatus.CONTAINS)),
            DisclosureRegistrationStatus.NOT_APPLICABLE,
            List.of(), false);
}
```

- [ ] **Step 2: Write failing Store and identity contract tests**

Add tests that call the not-yet-existing public method:

```java
@Test
void transactionEligibilityRejectsMissingStoreBeforeLoadingMenu() {
    given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.empty());

    assertThatThrownBy(() ->
            storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getErrorCode())
            .isEqualTo(StoreErrorCode.STORE_NOT_FOUND);

    then(menuRepository).shouldHaveNoInteractions();
}

@Test
void transactionEligibilityRejectsTemporarilyClosedStore() {
    Store store = storeOwnedBy(OPERATOR_ID);
    ReflectionTestUtils.setField(store, "id", STORE_ID);
    ReflectionTestUtils.setField(
            store, "operationStatus", OperationStatus.TEMPORARILY_CLOSED);
    given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));

    assertThatThrownBy(() ->
            storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getErrorCode())
            .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);

    then(menuRepository).shouldHaveNoInteractions();
}

@Test
void transactionEligibilityHidesWrongStoreOwnershipAsMissingMenu() {
    Store store = storeOwnedBy(OPERATOR_ID);
    ReflectionTestUtils.setField(store, "id", STORE_ID);
    Menu menu = menu(STORE_ID + 1, true, true);
    given(storeRepository.findByIdForUpdate(STORE_ID)).willReturn(Optional.of(store));
    given(menuRepository.findByIdForUpdate(MENU_ID)).willReturn(Optional.of(menu));

    assertThatThrownBy(() ->
            storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID))
            .isInstanceOf(ServiceException.class)
            .extracting(error -> ((ServiceException) error).getErrorCode())
            .isEqualTo(StoreErrorCode.MENU_NOT_FOUND);
}
```

Also add separate cases for a `null` verification status as the currently
representable non-approved state, `CLOSED`, and a missing Menu. Each case must
assert the exact approved error and that later dependencies are untouched.

- [ ] **Step 3: Write failing Menu-state contract tests**

Create a valid Store stub for every case, then mutate a Menu through its public
domain API:

```java
@Test
void transactionEligibilityRejectsUnpublishedMenu() {
    Menu menu = menu(STORE_ID, true, true);
    stubTransactionStoreAndMenu(menu);

    assertMenuStateConflict(() ->
            storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID));
}

@Test
void transactionEligibilityRejectsHiddenMenu() {
    Menu menu = publishedMenu(true, true);
    menu.changeVisibility(MenuVisibility.HIDDEN);
    stubTransactionStoreAndMenu(menu);

    assertMenuStateConflict(() ->
            storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID));
}

@Test
void transactionEligibilityRejectsPausedAndSoldOutMenus() {
    for (MenuSellingStatus status :
            List.of(MenuSellingStatus.PAUSED, MenuSellingStatus.SOLD_OUT)) {
        Menu menu = publishedMenu(true, true);
        menu.changeSellingStatus(status);
        stubTransactionStoreAndMenu(menu);

        assertMenuStateConflict(() ->
                storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID));
        reset(storeRepository, menuRepository);
    }
}
```

Add a retired case by publishing and then calling `menu.retire(...)`. The shared
assertion must require `StoreErrorCode.MENU_STATE_CONFLICT`.

- [ ] **Step 4: Write the failing success and capability tests**

Verify both the current published version and final channel calculations:

```java
@Test
void transactionEligibilityReturnsCurrentPublishedVersionAndCapabilities() {
    Menu menu = publishedMenu(true, true);
    stubTransactionStoreAndMenu(menu);

    MenuTransactionEligibility result =
            storeService.requireMenuTransactionEligibility(STORE_ID, MENU_ID);

    assertThat(result).isEqualTo(new MenuTransactionEligibility(
            STORE_ID, MENU_ID, 1, true, true));
    then(storeRepository).should().findByIdForUpdate(STORE_ID);
    then(menuRepository).should().findByIdForUpdate(MENU_ID);
    then(operatorAccountService).shouldHaveNoInteractions();
}
```

Add parameterized or separate cases proving:

```text
Store menuHoldEnabled=false OR version holdSelectionAllowed=false
    -> menuHoldEligible=false

Store pickupEnabled=false OR Store pickupEligibility=INELIGIBLE
OR version pickupSelectionAllowed=false
    -> pickupEligible=false
```

Keep the Menu otherwise `PUBLISHED + VISIBLE + SELLING`; a false channel
capability remains a successful DTO result.

- [ ] **Step 5: Run the tests and verify the red state**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreServiceTest"
```

Expected: compilation fails because `MenuTransactionEligibility`,
`requireMenuTransactionEligibility`, and `Menu.requireTransactionVersion` do not
exist and `StoreService` does not yet accept `MenuRepository`.

- [ ] **Step 6: Implement the immutable DTO**

Create:

```java
package com.miriyum.domain.store.menu.dto;

public record MenuTransactionEligibility(
        long storeId,
        long menuId,
        int publishedVersionNumber,
        boolean menuHoldEligible,
        boolean pickupEligible
) {
}
```

- [ ] **Step 7: Implement Menu-owned lifecycle validation**

Add `MenuVersionStatus` import and this method to `Menu`:

```java
public MenuVersion requireTransactionVersion() {
    requireActive();
    MenuVersion published = findVersion(publishedVersionNumber)
            .orElseThrow(Menu::stateConflict);
    if (published.getStatus() != MenuVersionStatus.PUBLISHED
            || visibility != MenuVisibility.VISIBLE
            || sellingStatus != MenuSellingStatus.SELLING) {
        throw stateConflict();
    }
    return published;
}
```

Do not expose the versions collection or let `StoreService` reproduce these
conditions from aggregate getters.

- [ ] **Step 8: Implement Store orchestration and final capability composition**

Inject `MenuRepository`, import the DTO, Menu, MenuVersion, and
`PickupEligibility`, then add:

```java
@Transactional(isolation = Isolation.READ_COMMITTED, timeout = 5)
public MenuTransactionEligibility requireMenuTransactionEligibility(
        long storeId,
        long menuId
) {
    Store store = storeRepository.findByIdForUpdate(storeId)
            .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
    requireTransactionState(store);

    Menu menu = menuRepository.findByIdForUpdate(menuId)
            .orElseThrow(() -> new ServiceException(StoreErrorCode.MENU_NOT_FOUND));
    if (menu.getStoreId() != storeId) {
        throw new ServiceException(StoreErrorCode.MENU_NOT_FOUND);
    }

    MenuVersion published = menu.requireTransactionVersion();
    boolean menuHoldEligible = store.isMenuHoldEnabled()
            && published.isHoldSelectionAllowed();
    boolean pickupEligible = store.isPickupEnabled()
            && store.getPickupEligibility() == PickupEligibility.ELIGIBLE
            && published.isPickupSelectionAllowed();
    return new MenuTransactionEligibility(
            storeId,
            menuId,
            published.getVersionNumber(),
            menuHoldEligible,
            pickupEligible);
}

private void requireTransactionState(Store store) {
    if (store.getVerificationStatus() != VerificationStatus.APPROVED) {
        throw new ServiceException(StoreErrorCode.VERIFICATION_STATE_CONFLICT);
    }
    if (store.getOperationStatus() != OperationStatus.OPEN) {
        throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
    }
}
```

Keep `requireScheduleState` unchanged because publication commands may retain
their existing `TEMPORARILY_CLOSED` behavior.

- [ ] **Step 9: Run the focused contract test and verify green**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.service.StoreServiceTest"
```

Expected: `BUILD SUCCESSFUL` with zero failed tests.

- [ ] **Step 10: Commit the working contract**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java backend/src/main/java/com/miriyum/domain/store/menu/entity/Menu.java backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuTransactionEligibility.java backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java
git commit -m "feat(store): expose menu transaction eligibility contract"
```

### Task 2: Verify Scope, Regression, and Delivery Evidence

**Files:**
- Verify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Verify: `backend/src/main/java/com/miriyum/domain/store/menu/entity/Menu.java`
- Verify: `backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuTransactionEligibility.java`
- Verify: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`
- Verify: `docs/superpowers/specs/2026-08-03-menu-transaction-eligibility-design.md`
- Verify: `docs/superpowers/plans/2026-08-03-menu-transaction-eligibility.md`

**Interfaces:**
- Consumes: the complete Task 1 contract.
- Produces: test, scope, and formatting evidence suitable for the #85 Draft PR.

- [ ] **Step 1: Run aggregate and Store contract tests together**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.menu.entity.MenuTest" --tests "com.miriyum.domain.store.core.service.StoreServiceTest"
```

Expected: `BUILD SUCCESSFUL` with zero failures.

- [ ] **Step 2: Run all Store domain tests**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.*"
```

Expected: `BUILD SUCCESSFUL` with zero failures.

- [ ] **Step 3: Run the complete backend test suite**

```powershell
.\gradlew.bat clean test
```

Expected: `BUILD SUCCESSFUL`. Record tests, failures, errors, and skipped counts
from `build/test-results/test/TEST-*.xml` for the PR.

- [ ] **Step 4: Verify contract isolation and exact scope**

```powershell
git diff --name-only origin/dev...HEAD
rg -n "domain\.store\.(core|menu)\.(entity|repository|enums)" backend/src/main/java/com/miriyum/domain/menuhold
git diff --check origin/dev...HEAD
```

Expected:

- only the approved production/test files and design/plan documents changed;
- no `domain/menuhold/**` file imports Store/Menu entity, repository, or enum;
- `git diff --check` exits zero.

- [ ] **Step 5: Review the public surface**

Confirm the DTO exposes no Entity or enum, the service method takes only
`storeId + menuId`, Store is locked before Menu, wrong ownership is hidden as
`STORE_009`, and no Controller/API/migration/error code was added.

- [ ] **Step 6: Commit verification corrections only if required**

If verification required a production or test correction, stage only the
approved files and commit:

```powershell
git commit -m "fix(store): harden menu transaction eligibility contract"
```

If no correction was needed, do not create an empty commit.

- [ ] **Step 7: Push and open a Draft PR**

Push `codex/85-menu-transaction-eligibility`, then create a Draft PR targeting
`dev`. The PR body must include:

- `Closes #85` and `Blocks #42`;
- the exact public method and DTO;
- the Store/Menu validation and error precedence;
- Store-then-Menu lock order;
- focused, Store-domain, full-suite, and `git diff --check` evidence;
- explicit confirmation that `domain/menuhold/**`, HTTP APIs, migrations, and
  error codes were not changed.

## Self-Review

- Spec coverage: Store state, Menu identity/ownership, current publication,
  visibility, selling state, retirement, version projection, channel capability
  composition, lock order, error precedence, and isolation all map to Task 1.
- Placeholder scan: no `TBD`, `TODO`, “implement later,” or unspecified code/test
  steps remain.
- Type consistency: method, DTO constructor, fields, repository calls, and test
  names use the same signatures throughout the plan.
