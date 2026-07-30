# Store Menu Issue #35 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the first-MVP store-operator menu management API from draft creation through publication, scheduling, independent visibility/selling controls, and retirement.

**Architecture:** `Menu` is the locked aggregate root and owns lifecycle pointers and independent exposure controls. Immutable `MenuVersion` rows hold editable content; every update appends a new draft, while `MenuPublicationEvent` is an append-only audit stream. Store-operator commands run through the existing idempotency layer, and a bounded scheduled worker activates due versions using database time and row locks.

**Tech Stack:** Java 21, Spring Boot 4, Spring MVC/Security, Spring Data JPA, Hibernate, Flyway, MySQL 8, JUnit 5, Mockito, MockMvc, Testcontainers, Gradle.

## Global Constraints

- Work only in `C:\Users\lbw01\GitHub\Commerce-Final-Project-MiriYum\.superpowers\sdd\worktrees\store-menu-35` on `codex/35-store-menu`.
- `POST /menus` is the required UI save operation and creates a `DRAFT`; `PUT /menus/{menuId}` is optional CRUD update only and is never required before publication.
- `POST /publication` publishes immediately or schedules the current draft; `DELETE /publication` cancels only a scheduled publication.
- Lifecycle states are exactly `DRAFT`, `SCHEDULED`, `PUBLISHED`, and `RETIRED`.
- Exposure axes are independent: visibility is `VISIBLE`/`HIDDEN`, selling status is `SELLING`/`PAUSED`.
- First publication sets `VISIBLE` and `SELLING`; replacement publication preserves both current axis values.
- Retirement is domain soft deletion; no hard-delete or restore endpoint is part of issue #35.
- Quantity, `AVAILABLE`/`SOLD_OUT`, images, options, public menu endpoints, and transaction execution are outside issue #35.
- Store local tags: at most 10 distinct NFKC-normalized values, each 1–30 Unicode code points after trim; reject controls, URLs, email/contact handles, and reserved official/admin impersonation terms.
- Menu primary category is required; secondary categories contain 0–5 distinct active catalog codes and may not repeat the primary category.
- Price is a non-negative integer in KRW.
- Pickup selection requires store `PickupEligibility.ELIGIBLE`; hold selection is content configuration and downstream execution must also check the store mode.
- Writes require a store-operator token and a valid `Idempotency-Key`; each command type has a distinct fingerprint namespace.
- Use migration `V10__create_store_menus.sql`. Issue #34 owns V9, so rebase #35 after #34 merges before production integration.

---

## File Structure

- `domain/store/menu/entity`: aggregate root, immutable content version, publication event.
- `domain/store/menu/enums`: lifecycle, visibility, selling, publication mode/event types.
- `domain/store/menu/dto`: validated write requests and management read responses.
- `domain/store/menu/repository`: locked aggregate lookup, version/event persistence, due-schedule claim query.
- `domain/store/menu/service`: content policy/fingerprint, command orchestration, read assembly, scheduled activation.
- `domain/store/menu/controller`: store-operator HTTP contract.
- `resources/db/migration/V10__create_store_menus.sql`: relational constraints, indexes, and restrictive foreign keys.
- Matching `src/test` packages: domain rules, DTO/policy/fingerprint tests, controller slice tests, service tests, and MySQL integration tests.

### Task 1: Persistence Contract and Aggregate Lifecycle

**Files:**
- Create: `backend/src/main/resources/db/migration/V10__create_store_menus.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/enums/MenuVersionStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/enums/MenuVisibility.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/enums/MenuSellingStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/enums/MenuPublicationEventType.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/entity/Menu.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/entity/MenuVersion.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/entity/MenuPublicationEvent.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/entity/MenuTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/repository/MenuMigrationTest.java`

**Interfaces:**
- Produces: `Menu.create(long storeId)`, `Menu.appendDraft(MenuVersion)`, `Menu.publish(Instant)`, `Menu.schedule(Instant, Instant)`, `Menu.cancelSchedule(Instant)`, `Menu.changeVisibility(MenuVisibility)`, `Menu.changeSellingStatus(MenuSellingStatus)`, and `Menu.retire(Instant)`.
- Produces: immutable version factory `MenuVersion.draft(Menu, int, MenuContent, long, Instant)`.

- [ ] **Step 1: Write failing lifecycle tests**

Cover creation defaults (`HIDDEN`, `PAUSED`, version 1 draft), update retirement of only the previous draft, immediate replacement, first-publication axes, scheduled replacement/cancellation, independent axes, invalid transitions, and retirement clearing all pointers.

- [ ] **Step 2: Run the focused lifecycle test**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.menu.entity.MenuTest"`

Expected: FAIL because menu aggregate types do not exist.

- [ ] **Step 3: Implement enums and aggregate entities**

Implement pointer changes only through aggregate methods. Published and scheduled version content must never be mutated; a replaced draft/scheduled/published version transitions to `RETIRED`.

- [ ] **Step 4: Write the failing migration test**

Assert all five tables exist, `(menu_id, version_number)` is unique, a menu references one store, content collections reference versions, and store/menu/version deletion is restrictive rather than cascading away audit history.

- [ ] **Step 5: Implement V10 migration**

Create `menus`, `menu_versions`, `menu_version_secondary_categories`, `menu_version_local_tags`, and `menu_publication_events`, with check constraints for enums, price, version numbers, pointer distinctness, and indexed scheduled-effective lookup.

- [ ] **Step 6: Run lifecycle and migration tests**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.menu.entity.MenuTest" --tests "com.miriyum.domain.store.menu.repository.MenuMigrationTest"`

Expected: PASS.

- [ ] **Step 7: Commit**

Commit: `feat: add menu versioning aggregate`

### Task 2: Input Validation and Content Policy

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/model/MenuContent.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuContentRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuContentPolicy.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/dto/MenuContentRequestTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuContentPolicyTest.java`

**Interfaces:**
- Consumes: `CatalogService.findUnknownCodes(CatalogKind.MENU_CATEGORY, Collection<String>)`.
- Produces: `MenuContentPolicy.validateAndNormalize(MenuContentRequest, StoreManagementView)` returning `MenuContent`.

- [ ] **Step 1: Write failing DTO and policy tests**

Test required name/category, description and name length, non-negative integer price, secondary count and duplication, primary overlap, unknown/inactive categories (`STORE_004`), pickup ineligibility (`STORE_008`), local-tag normalization/deduplication/count/length/control/contact/URL/impersonation rejection (`COMMON_001`).

- [ ] **Step 2: Run focused policy tests**

Run: `.\gradlew.bat test --tests "*MenuContentRequestTest" --tests "*MenuContentPolicyTest"`

Expected: FAIL because request and policy types do not exist.

- [ ] **Step 3: Implement request, normalized model, and policy**

Normalize local tags with `Normalizer.Form.NFKC`, trim, preserve first-seen order, and compare duplicates case-insensitively. Validate category codes through the existing catalog service and never promote local tags to the global catalog.

- [ ] **Step 4: Run focused policy tests**

Run: `.\gradlew.bat test --tests "*MenuContentRequestTest" --tests "*MenuContentPolicyTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat: validate menu draft content`

### Task 3: Repositories and Management Read Model

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/repository/MenuRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/repository/MenuVersionRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/repository/MenuPublicationEventRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuVersionResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/dto/ManagedMenuResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuQueryService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/repository/MenuRepositoryIT.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuQueryServiceTest.java`

**Interfaces:**
- Produces: `MenuRepository.findManagedByIdForUpdate(long menuId)`, `findAllByStoreIdOrderById(long storeId)`, and `findDueScheduledIds(Instant databaseNow, Pageable page)`.
- Produces: `MenuQueryService.list(long operatorId, long storeId)` and `get(long operatorId, long storeId, long menuId)`.

- [ ] **Step 1: Write failing repository and query tests**

Verify pessimistic root locking, ordered store-scoped list, wrong-store `STORE_009`, retired menu inclusion for management history, eager response assembly without detached lazy access, and due-schedule ordering.

- [ ] **Step 2: Run focused tests**

Run: `.\gradlew.bat test --tests "*MenuRepositoryIT" --tests "*MenuQueryServiceTest"`

Expected: FAIL because repositories/query service do not exist.

- [ ] **Step 3: Implement repositories and management responses**

Every query first calls `StoreService.requireManagementAuthority`; a menu that exists under another store must return `STORE_009` and must not reveal ownership.

- [ ] **Step 4: Run focused tests**

Run: `.\gradlew.bat test --tests "*MenuRepositoryIT" --tests "*MenuQueryServiceTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat: add managed menu queries`

### Task 4: Idempotent Draft Create and Optional Update

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuCommandFingerprint.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuCommandResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuCommandService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuCommandFingerprintTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuCommandServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuCommandServiceIT.java`

**Interfaces:**
- Produces: `create(operatorId, storeId, IdempotencyKey, MenuContentRequest)` returning HTTP 201 response.
- Produces: `update(operatorId, storeId, menuId, IdempotencyKey, MenuContentRequest)` returning HTTP 200 response.

- [ ] **Step 1: Write failing fingerprint and service tests**

Assert deterministic canonical fingerprints, distinct `MENU_CREATE`/`MENU_UPDATE` namespaces, authority/state checks (`STORE_001/003/005/007/009/010`), exact replay, conflicting replay, create v1 draft, and update appending v2 while retiring only v1 draft.

- [ ] **Step 2: Run focused command tests**

Run: `.\gradlew.bat test --tests "*MenuCommandFingerprintTest" --tests "*MenuCommandServiceTest" --tests "*MenuCommandServiceIT"`

Expected: FAIL because command service does not exist.

- [ ] **Step 3: Implement create and update commands**

Reject permanently closed or unapproved stores; allow temporarily closed stores. Lock the root before appending an update and persist idempotency record, aggregate, version, and response atomically.

- [ ] **Step 4: Run focused command tests**

Run: `.\gradlew.bat test --tests "*MenuCommandFingerprintTest" --tests "*MenuCommandServiceTest" --tests "*MenuCommandServiceIT"`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat: save and revise menu drafts`

### Task 5: Publication, Scheduling, Cancellation, and Audit

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/enums/MenuPublicationMode.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuPublicationRequest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuCommandService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/dto/MenuPublicationRequestTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuPublicationServiceIT.java`

**Interfaces:**
- Produces: `publish(operatorId, storeId, menuId, IdempotencyKey, MenuPublicationRequest)`.
- Produces: `cancelPublication(operatorId, storeId, menuId, IdempotencyKey)`.

- [ ] **Step 1: Write failing request and transition tests**

Test `IMMEDIATE` forbids `effectiveAt`, `SCHEDULED` requires a future ISO offset instant, draft requirement, first/immediate replacement behavior, scheduled replacement, cancellation, command replay, and one audit event per actual transition.

- [ ] **Step 2: Run focused publication tests**

Run: `.\gradlew.bat test --tests "*MenuPublicationRequestTest" --tests "*MenuPublicationServiceIT"`

Expected: FAIL because publication commands do not exist.

- [ ] **Step 3: Implement publication commands**

Use the database current timestamp for transition validation/recording. Immediate publication cancels and retires an outstanding scheduled version, retires the old published version, and activates the draft atomically.

- [ ] **Step 4: Run focused publication tests**

Run: `.\gradlew.bat test --tests "*MenuPublicationRequestTest" --tests "*MenuPublicationServiceIT"`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat: publish and schedule menu versions`

### Task 6: Visibility, Selling Status, and Retirement

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuVisibilityRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/dto/MenuSellingStatusRequest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuCommandService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuControlServiceIT.java`

**Interfaces:**
- Produces: `changeVisibility(...)`, `changeSellingStatus(...)`, and `retire(...)`, all idempotent and returning the management response.

- [ ] **Step 1: Write failing control tests**

Verify hiding does not pause, pausing does not hide, showing does not resume, resuming does not show, controls reject retired menus, and retirement retires all active pointers, records audit, forces hidden/paused, and preserves every row.

- [ ] **Step 2: Run focused control tests**

Run: `.\gradlew.bat test --tests "*MenuControlServiceIT"`

Expected: FAIL because control commands do not exist.

- [ ] **Step 3: Implement independent controls and soft retirement**

Give visibility, selling, and retirement separate idempotency command types and fingerprints. Do not expose any repository delete path from the service.

- [ ] **Step 4: Run focused control tests**

Run: `.\gradlew.bat test --tests "*MenuControlServiceIT"`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat: control and retire managed menus`

### Task 7: Scheduled Activation Worker

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuScheduleActivator.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/service/MenuScheduleWorker.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/config/MenuScheduleConfig.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuScheduleActivatorIT.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/service/MenuScheduleWorkerTest.java`

**Interfaces:**
- Produces: `MenuScheduleActivator.activateDue(long menuId)` with `REQUIRES_NEW`.
- Produces: `MenuScheduleWorker.activateDueBatch()` scheduled at a configurable fixed delay and bounded batch size.

- [ ] **Step 1: Write failing activation tests**

Verify pre-effective no-op, due activation, replacement of published version, cancelled/retired no-op, repeat-call idempotence, two-worker serialization, rollback without partial pointer/event changes, and deterministic batch bounds.

- [ ] **Step 2: Run focused worker tests**

Run: `.\gradlew.bat test --tests "*MenuScheduleActivatorIT" --tests "*MenuScheduleWorkerTest"`

Expected: FAIL because worker classes do not exist.

- [ ] **Step 3: Implement worker and transaction boundary**

Select candidate IDs in bounded order, then lock and activate each aggregate in its own transaction. Compare effective time with a database-sourced current instant inside the locked transaction and append exactly one `SCHEDULE_ACTIVATED` event.

- [ ] **Step 4: Run focused worker tests**

Run: `.\gradlew.bat test --tests "*MenuScheduleActivatorIT" --tests "*MenuScheduleWorkerTest"`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat: activate scheduled menu publications`

### Task 8: Store-Operator HTTP API

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/menu/controller/MenuController.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/controller/MenuControllerTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/menu/config/MenuSecurityIT.java`

**Interfaces:**
- Exposes:
  - `GET /api/v1/store-operator/stores/{storeId}/menus`
  - `GET /api/v1/store-operator/stores/{storeId}/menus/{menuId}`
  - `POST /api/v1/store-operator/stores/{storeId}/menus`
  - `PUT /api/v1/store-operator/stores/{storeId}/menus/{menuId}`
  - `POST|DELETE /api/v1/store-operator/stores/{storeId}/menus/{menuId}/publication`
  - `PATCH /api/v1/store-operator/stores/{storeId}/menus/{menuId}/visibility`
  - `PATCH /api/v1/store-operator/stores/{storeId}/menus/{menuId}/selling-status`
  - `POST /api/v1/store-operator/stores/{storeId}/menus/{menuId}/retirement`

- [ ] **Step 1: Write failing MockMvc and security tests**

Cover all success envelopes/statuses, validation errors, missing/invalid idempotency key, missing/consumer credentials, store/menu authorization error mapping, and exact request-to-service routing.

- [ ] **Step 2: Run focused controller tests**

Run: `.\gradlew.bat test --tests "*MenuControllerTest" --tests "*MenuSecurityIT"`

Expected: FAIL because controller does not exist.

- [ ] **Step 3: Implement controller**

Keep the existing `/api/v1/store-operator/stores/**` security boundary. Only mutating endpoints parse `Idempotency-Key`; management reads do not require it.

- [ ] **Step 4: Run focused controller tests**

Run: `.\gradlew.bat test --tests "*MenuControllerTest" --tests "*MenuSecurityIT"`

Expected: PASS.

- [ ] **Step 5: Commit**

Commit: `feat: expose store menu management api`

### Task 9: Cross-Cut Verification and PR Delivery

**Files:**
- Modify only files required by failures attributable to issue #35.

- [ ] **Step 1: Run all menu tests**

Run: `.\gradlew.bat test --tests "com.miriyum.domain.store.menu.*"`

Expected: PASS with zero failures/errors.

- [ ] **Step 2: Run the full backend suite**

Run: `.\gradlew.bat clean test`

Expected: `BUILD SUCCESSFUL`; record total tests, failures, errors, and skipped counts.

- [ ] **Step 3: Review migration and diff**

Run: `git diff codex/33-store-core...HEAD --check`

Run: `git status --short`

Confirm no quantity/SOLD_OUT, image, option, public endpoint, or unrelated #34 implementation entered the diff; confirm V10 and the #34-rebase note are present.

- [ ] **Step 4: Final commit if verification required corrections**

Commit: `fix: harden store menu lifecycle`

- [ ] **Step 5: Push and open Draft PR**

Push `codex/35-store-menu`, then open a Draft PR targeting `codex/33-store-core`. The PR body must map the API roles in plain language, list lifecycle/control behavior, include exact test evidence, say that V10 assumes #34's V9 and therefore requires rebase after #34 merges, and close issue #35.

## Self-Review

- Spec coverage: all lifecycle states, both independent control axes, create/update role separation, management reads, every required mutation, store/catalog/pickup authority, local tags, idempotency, immutable history, scheduling concurrency, and soft retirement map to Tasks 1–8.
- Scope exclusions are repeated in Global Constraints and verified in Task 9.
- Placeholder scan: no `TBD`, `TODO`, “implement later,” or unspecified error-handling/test steps remain.
- Type consistency: controller methods route to `MenuCommandService`/`MenuQueryService`; repositories and aggregate methods used in later tasks are introduced in Tasks 1 and 3.
