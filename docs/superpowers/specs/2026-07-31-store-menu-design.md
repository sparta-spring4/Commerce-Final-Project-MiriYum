# Store Menu Versioning and Publication Design

**Date:** 2026-07-31
**Issue:** #35
**Base:** `codex/33-store-core`
**Scope:** store-operator menu management backend for the first MVP

## Goal

Implement the first-MVP menu policy without reducing its lifecycle. A store
operator can save menu content, revise it through immutable versions, publish it
immediately or at a future instant, cancel a scheduled publication, control
visibility and selling independently, and retire a menu without deleting
historical references.

The implementation includes text menu information only. Images, S3, quantity
ledgers, `SOLD_OUT` automation, menu-hold transactions, pickup transactions,
options, and public search endpoints remain outside Issue #35.

## User-Visible Management Flow

The normal create-and-publish flow is:

1. The operator completes the menu creation screen and selects save.
2. The create API stores version 1 as `DRAFT`.
3. The management list shows the saved menu as unpublished.
4. The operator selects either immediate publication or scheduled publication.
5. Immediate publication makes the draft effective now. Scheduled publication
   makes it effective at the requested future instant.

Updating a menu is not a mandatory step in this flow. The update API is the CRUD
update operation and is called only when the operator edits saved content.

For an already published menu, an edit creates a new draft while the existing
published version remains effective. The replacement occurs only when the new
draft is published or its scheduled publication becomes effective.

The first publication starts as `VISIBLE` and `SELLING`. After publication, the
operator can independently hide/show and pause/resume selling. Retirement removes
the menu from public and new-transaction candidates while preserving all versions
for historical references.

## Domain Model

### Stable menu identity

`Menu` owns the long-lived `menuId`, `storeId`, next version number, concurrency
version, and pointers to at most one current draft, scheduled version, and
published version.

It also owns two independent operational axes:

- visibility: `VISIBLE` or `HIDDEN`
- selling: `SELLING` or `PAUSED`

These values are separate columns and enums. No combined status enum is allowed.

### Immutable menu content version

`MenuVersion` is append-only for content and contains:

- per-menu version number
- name and text description
- non-negative integer KRW price; currency is fixed by the server and is not a
  request field
- representative-menu flag
- one active primary menu-category code
- zero to five active, distinct secondary menu-category codes
- normalized, distinct store-local text tags
- menu-hold selection allowed
- pickup selection allowed
- creator account ID and creation time

Primary and secondary category codes cannot overlap. Categories are validated
through #31 `CatalogService` and protected by database foreign keys.

A content update never modifies an existing `MenuVersion`. It retires the
replaceable draft, creates the next version, and moves only the draft pointer.
Published, scheduled, and historical content rows are never overwritten.

### Publication lifecycle

The first-MVP lifecycle is retained in full:

- `DRAFT`: saved but not selected for publication
- `SCHEDULED`: selected for a future effective instant
- `PUBLISHED`: currently effective content
- `RETIRED`: no longer a publication candidate, retained for history

Lifecycle transitions and their actor, command ID, effective instant, and
confirmation time are recorded in append-only `MenuPublicationEvent` rows.
Content remains immutable even when its lifecycle changes.

The root row lock and database uniqueness constraints guarantee at most one
draft, one scheduled version, and one effective published version for a menu.

## State Transitions

### Create and update

- Create produces menu version 1 as `DRAFT`, with `HIDDEN` and `PAUSED`.
- Update is permitted for a managed, non-retired menu.
- Update retires only the previous draft and creates a new draft.
- An existing scheduled or published version is not silently replaced by save.

### Immediate publication

- Requires a current draft.
- Retires the currently published version, if any.
- Cancels and retires a previously scheduled replacement, if any.
- Promotes the draft to `PUBLISHED` in the same transaction.
- First publication changes operational defaults to `VISIBLE` and `SELLING`.
- Replacement publication preserves the menu's existing visibility and selling
  controls.

### Scheduled publication

- Requires a current draft and a future ISO-8601 offset timestamp.
- Replaces and retires a previously scheduled version, if any.
- Moves the draft to `SCHEDULED`.
- Keeps the currently published version effective until the scheduled instant.
- A scheduled worker claims due menus with database locking and atomically retires
  the prior published version and activates the scheduled version.
- Domain reads and commands compare the central database time with the scheduled
  effective instant, so a delayed worker cannot make an expired old version valid
  for a new transaction.

### Scheduled-publication cancellation

- Requires a current `SCHEDULED` version whose effective instant has not passed.
- Retires the scheduled publication without changing the current published
  version.
- The operator may edit again to create a new draft.

### Visibility and selling controls

- Visibility changes only between `VISIBLE` and `HIDDEN`.
- Selling changes only between `SELLING` and `PAUSED`.
- `HIDDEN` blocks public listing, direct-link selection, and new transactions.
- `PAUSED` may remain visible but blocks new selection.
- Showing a paused menu does not resume selling.
- Resuming a hidden menu does not make it visible.
- Issue #35 does not store `AVAILABLE` or `SOLD_OUT`; quantity-driven status
  belongs to the menu-hold/pickup domain.

### Retirement

- Retirement is a domain soft delete, represented by lifecycle `RETIRED`, not a
  generic `deleted` flag.
- It retires the effective published, scheduled, and draft versions under one
  menu lock, clears current pointers, and sets `HIDDEN` plus `PAUSED`.
- It does not delete the menu, versions, categories, tags, or publication events.
- Public and new-transaction queries exclude retired menus.
- Issue #35 does not add restoration or hard-delete behavior.

## API Contract

All store-operator routes require a store-operator access token. Every write
requires a valid `Idempotency-Key`.

### Management reads

- `GET /api/v1/store-operator/stores/{storeId}/menus`
- `GET /api/v1/store-operator/stores/{storeId}/menus/{menuId}`

These endpoints support the future management list and edit screen. Responses
identify the stable menu, current draft/scheduled/published version numbers,
scheduled instant, visibility, selling status, and the content the operator is
editing. Retired menus remain available to authorized management reads.

### Content writes

- `POST /api/v1/store-operator/stores/{storeId}/menus`
  - required create flow; saves version 1 as a draft
- `PUT /api/v1/store-operator/stores/{storeId}/menus/{menuId}`
  - optional CRUD update; called only when the operator edits content

Both accept the complete menu content. PUT is replacement of editable content,
not a partial patch and not a required pre-publication step.

### Publication commands

- `POST /api/v1/store-operator/stores/{storeId}/menus/{menuId}/publication`
  - body mode `IMMEDIATE` or `SCHEDULED`
  - `effectiveAt` is required only for `SCHEDULED`
- `DELETE /api/v1/store-operator/stores/{storeId}/menus/{menuId}/publication`
  - cancels a future scheduled publication
- `POST /api/v1/store-operator/stores/{storeId}/menus/{menuId}/retirement`
  - retires the menu

The publication endpoint is the backend operation invoked by immediate/scheduled
publish actions in the management list. It is not an extra content-edit step.

### Independent operational controls

- `PATCH /api/v1/store-operator/stores/{storeId}/menus/{menuId}/visibility`
- `PATCH /api/v1/store-operator/stores/{storeId}/menus/{menuId}/selling-status`

Each route changes one axis only. A request cannot alter visibility and selling in
the same command.

## Validation and Authority

Every command first calls #33
`StoreService.requireManagementAuthority(operatorAccountId, storeId)`.

- missing store: `STORE_001`
- different representative operator: `STORE_003`
- permanently closed store: `STORE_005`
- unapproved store: `STORE_007`
- missing menu or menu belonging to another store: `STORE_009`
- invalid lifecycle or operational transition: `STORE_010`

Temporarily closed stores may prepare and publish menu content, but menu state
changes do not reopen the store or make a transaction executable.

Category validation uses #31:

- inactive or unknown category: `STORE_004`
- duplicate secondary code or overlap with primary: `COMMON_001`

Pickup selection is allowed only when #33 reports
`PickupEligibility.ELIGIBLE`; violations return `STORE_008`. Menu-hold selection
may be configured independently, but downstream execution must still check the
store-level menu-hold feature and the current published menu snapshot.

Text fields are trimmed and normalized. Local tags are store-scoped, normalized
for duplicate detection, length-bounded, and rejected for control characters,
contact/URL forms, platform impersonation terms, and the repository-owned basic
forbidden-term list. They never become global catalog codes.

## Idempotency and Concurrency

Distinct command types are used for create, update, immediate publish, scheduled
publish, schedule cancellation, visibility, selling, and retirement.

Fingerprints include route identity, store ID, menu ID where present, canonical
content, target state, and scheduled instant. Equivalent category/tag order
produces the same fingerprint.

Authority validation, idempotency execution, menu-row lock, version allocation,
state transition, append-only audit event, and stored response complete in one
bounded `READ_COMMITTED` transaction.

Different keys racing on the same menu serialize on the menu root row. Database
constraints and the root lock prevent duplicate version numbers and multiple
active versions. An unexpected persistence failure is not converted into a
business conflict unless it matches a known lifecycle or uniqueness race.

## Scheduled Activation

A small Spring scheduled worker processes due publications in bounded batches.
It uses database time and row locking suitable for multiple application
instances. Repeated or concurrent worker execution is idempotent.

The worker is an activation mechanism, not the source of truth. The database
effective instant and menu version state remain authoritative. Worker failure
does not roll back a previously confirmed scheduled command; recovery processes
the same due row later.

## Persistence and Migration Ordering

Issue #35 is developed from `codex/33-store-core`, while Issue #34 independently
uses Flyway V10. Issue #35 therefore reserves V11 and must be rebased after Issue
#34 is merged before production integration. V11 must not be deployed ahead of
V10. The PR records this temporary stacked migration-order dependency.

Tables cover:

- stable menus and concurrency/version pointers
- immutable menu-version headers
- secondary categories and local tags per version
- append-only publication/state events

Foreign keys are restrictive for historical data. Physical deletion of a store,
menu, or referenced version is not used as an operational transition.

## Testing

Tests are written before production code.

Unit tests cover:

- request validation and canonical fingerprints
- category uniqueness and catalog rejection
- pickup eligibility
- create/update version immutability
- every valid and invalid lifecycle transition
- independent visibility/selling combinations
- scheduled-time boundaries

MockMvc tests cover authentication namespace, missing/invalid idempotency keys,
validation envelopes, authority errors, `STORE_004/008/009/010`, and success
responses for every management action.

MySQL Testcontainers tests cover:

- Flyway V11 and JPA mappings
- immutable historical versions
- one draft/scheduled/published version per menu
- two concurrent updates receiving distinct version numbers
- immediate-versus-scheduled publication races
- idempotent multi-instance scheduled activation
- rollback of menu/version/event/idempotency rows together
- restrictive historical foreign keys

Final verification runs focused menu tests, the complete backend clean build,
test-result counts, and `git diff --check` against `codex/33-store-core`.

## Explicit Exclusions

- images, upload routes, S3, and platform content-review workflows
- menu options and option pricing
- quantity ledgers, `AVAILABLE`, and `SOLD_OUT`
- menu-hold or pickup transaction execution
- public search and public menu endpoints owned by #36
- frontend implementation
- mutation of existing reservations, holds, payments, refunds, or snapshots
- identity-verification stub configuration changes
