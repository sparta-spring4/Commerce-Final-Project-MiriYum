# Menu Transaction Eligibility Contract Design

**Date:** 2026-08-03  
**Issue:** #85  
**Base:** `origin/dev`  
**Blocks:** #42

## Goal

Provide one Store-owned service contract that validates whether a menu can
participate in a new menu-hold or pickup transaction. Consumers receive only a
stable eligibility DTO and never inspect Store/Menu entities, repositories, or
internal lifecycle enums.

This closes the contract gap left after #33 established Store authority and #35
implemented the menu lifecycle.

## Scope

Production changes are limited to:

- `StoreService.java`
- `Menu.java`
- new `MenuTransactionEligibility.java`

The Store contract is verified through `StoreServiceTest.java`. No controller,
HTTP route, migration, error code, or `domain/menuhold` implementation is added.

## Public Contract

`StoreService` exposes:

```java
MenuTransactionEligibility requireMenuTransactionEligibility(
        long storeId,
        long menuId
)
```

The method is intended for an already authenticated internal domain service. It
does not accept an operator identity and does not perform management ownership
checks.

It returns only after all Store and Menu state checks pass. Ineligible state is
reported as the approved Store error rather than as raw state for the consumer
to interpret.

## Validation and Error Precedence

Validation follows the shared lock and information-disclosure order:

1. Load and lock Store by `storeId`.
   - Missing Store: `STORE_001`.
   - Verification status other than `APPROVED`: `STORE_007`.
   - Operation status `CLOSED`: `STORE_005`.
2. Load and lock Menu by `menuId`.
   - Missing Menu: `STORE_009`.
   - Menu belongs to another Store: `STORE_009`.
3. Ask the Menu aggregate for its current transaction version.
   - Retired Menu: `STORE_010`.
   - No current published version: `STORE_010`.
   - Current visibility is not `VISIBLE`: `STORE_010`.
   - Current selling status is not `SELLING`, including `PAUSED` and
     `SOLD_OUT`: `STORE_010`.

A scheduled replacement does not invalidate an existing published version. The
current published pointer remains authoritative until scheduled activation.

## Aggregate Responsibility

`Menu` owns the interpretation of its lifecycle pointers and exposure axes. A
new public domain method validates that the aggregate is active, has a current
published version, is visible, and is selling, then returns that `MenuVersion`.

`StoreService` orchestrates Store validation, Menu lookup and ownership, and DTO
assembly. It does not reproduce Menu lifecycle rules by reading several getters.

## DTO

`MenuTransactionEligibility` is an immutable record containing:

- `long storeId`
- `long menuId`
- `int publishedVersionNumber`
- `boolean menuHoldEligible`
- `boolean pickupEligible`

The two capability values are final Store-owned decisions:

```text
menuHoldEligible = store.menuHoldEnabled
                   AND publishedVersion.holdSelectionAllowed

pickupEligible = store.pickupEnabled
                 AND store.pickupEligibility == ELIGIBLE
                 AND publishedVersion.pickupSelectionAllowed
```

The DTO does not expose `Menu`, `MenuVersion`, `MenuVisibility`,
`MenuSellingStatus`, Store state enums, or repositories. A false capability is a
valid result when the menu is publicly sellable through the other channel. The
consumer may select only a capability already computed by this contract; it does
not reinterpret Store/Menu state.

## Transactions and Locking

The service method runs in a bounded `READ_COMMITTED` transaction and acquires
pessimistic locks in Store-then-Menu order. Existing Store/Menu mutation flows
already use this order. The caller can participate in the same transaction and
must acquire its own quantity locks only after this contract returns.

This prevents a transaction command from validating one menu state while a
concurrent management command changes the Store or Menu before quantity work is
performed.

## Tests

`StoreServiceTest` supplies contract-level coverage for:

- missing, unapproved, and closed Store;
- missing Menu and Menu belonging to another Store;
- retired, unpublished, hidden, paused, and sold-out Menu;
- valid `PUBLISHED + VISIBLE + SELLING` Menu;
- the current published version number in the returned DTO;
- combined Store/version menu-hold capability;
- combined Store eligibility/mode/version pickup capability;
- Store lock before Menu lock and no operator-account dependency.

Tests are written red-first. Focused verification runs
`StoreServiceTest`, followed by related Store/Menu tests and the complete backend
suite. `git diff --check` verifies patch formatting.

## Ownership Boundary

#85 owns the Store/Menu contract. #42 consumes it and must not import Store/Menu
entities, repositories, or state enums. #85 does not add menu-hold quantity
behavior, map Store failures to menu-hold errors, or modify #42 code.
