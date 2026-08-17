# PR #382 Store Sanction Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the four unresolved PR #382 review findings without weakening Store ownership, transaction safety, or the no-automatic-cancellation invariant.

**Architecture:** Store owns base/effective mode composition and an OPER-009 permanent-closure command. Admin-store supplies sanction and approval facts through DTO-only Store ports; feature projections contain only their direct effects, and every new transaction checks the active Store-scoped restriction under the Store lock.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, Hibernate JSON, MySQL 8, Flyway, JUnit 5, Mockito, AssertJ, Testcontainers, Gradle, Redocly.

## Global Constraints

- Work only in `feature/279-admin-store-sanctions`; never push `dev`.
- Do not change `membersupport/**`.
- Do not reuse Store operator HTTP endpoints for platform administration.
- Do not propagate one Store sanction to sibling Stores owned by the same account.
- Do not cancel, refund, or overwrite existing confirmed transactions.
- Run only the smallest unit tests and affected integration classes locally; do not run `build`, `check`, full `integrationTest`, or integration A–D as a group.
- Treat GitHub CI as the authoritative full regression result.

---

### Task 1: Freeze the active-document and exact-allowlist boundary

**Files:**
- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/specs/admin-store/spec.md`
- Verify: `ai/document-routing.md`

**Interfaces:**
- Consumes: active-spec routing rules from `ai/document-routing.md`.
- Produces: an explicit `docs/05-functional-requirements.md -> docs/specs/admin-store/spec.md` owner link and an allowlist identical to the approved PR paths.

- [ ] **Step 1: Generate the current path comparison**

Run:

```powershell
git diff --name-only origin/dev...HEAD
```

Compare it with every backticked path under `## 8. 정확한 변경 파일 allowlist`. Confirm that `DomainPackageArchitectureTest` and `AdminStoreQueryServiceTest` are not changed before removing them.

- [ ] **Step 2: Update the owner link and allowlist before production edits**

Add `[매장 제재 기능 명세](specs/admin-store/spec.md)` to the platform-operator row in `docs/05-functional-requirements.md`. Add that file to the contract allowlist, preserve the design and plan paths, and remove only paths that are neither in the current diff nor required by this remediation.

- [ ] **Step 3: Update the existing Issue #279 allowlist comment**

Edit issue comment `5309011379` so its path list, Store-owned contract rationale, and targeted verification plan match the active spec before writing production code.

- [ ] **Step 4: Verify the document change**

Run:

```powershell
git diff --check
git diff -- docs/05-functional-requirements.md docs/specs/admin-store/spec.md
```

Expected: no whitespace error; active-owner link and exact paths are visible.

### Task 2: Normalize per-sanction projections

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/model/StoreSanctionPolicyCatalogTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreAdministrationServiceIT.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/dto/administration/StoreAdministrationContracts.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/model/StoreSanctionPolicyCatalog.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/entity/StoreEnforcementState.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreAdministrationService.java`

**Interfaces:**
- Produces: `EnforcementCommand.operationStatusEffect(): OperationStatus`, nullable when the sanction has no direct status contribution.
- Produces: deterministic composition where `CLOSED > TEMPORARILY_CLOSED > baseOperationStatus` and restricted features are a union.

- [ ] **Step 1: Write failing projection tests**

Add tests equivalent to:

```java
@Test
void featureRestrictionHasNoOperationStatusEffect() {
    EnforcementCommand command = policy.command(7L, 0L, 91L, currentTemporarilyClosed(), featureRestriction());
    assertThat(command.operationStatusEffect()).isNull();
}

@Test
void releasingTemporarySuspensionDoesNotRetainStatusCopiedByFeatureRestriction() {
    // apply temporary, apply reservation restriction, release temporary
    assertThat(result.operationStatus()).isEqualTo(OperationStatus.OPEN);
    assertThat(result.restrictedFeatures()).containsExactly(RestrictedFeature.RESERVATION);
}
```

Cover both application orders and both release orders.

- [ ] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.platformoperator.adminstore.model.StoreSanctionPolicyCatalogTest" --tests "com.miriyum.domain.store.service.StoreAdministrationServiceIT"
```

Expected: failure because FEATURE_RESTRICTION currently copies the effective Store operation status.

- [ ] **Step 3: Implement direct-effect projections**

Change the command record and projection record to allow a nullable status effect:

```java
public record EnforcementCommand(
        long storeId,
        long expectedEnforcementVersion,
        long sanctionId,
        OperationStatus operationStatusEffect,
        Set<RestrictedFeature> restrictedFeatures) { }
```

Keep the existing effective booleans out of the persisted projection decision. In `StoreSanctionPolicyCatalog`, return `null` for FEATURE_RESTRICTION, `TEMPORARILY_CLOSED` for TEMPORARY_SUSPENSION, and reject PERMANENT_EXIT from this generic command. Recompose effective booleans from base settings plus the active feature union.

- [ ] **Step 4: Run GREEN**

Repeat the Task 2 test command. Expected: both classes pass.

- [ ] **Step 5: Commit the isolated projection fix**

Commit message:

```text
fix: normalize store sanction projections
```

### Task 3: Preserve latest base settings and enforce all transaction restrictions

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreTransactionEligibilityServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreAdministrationServiceIT.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/dto/administration/StoreAdministrationContracts.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/entity/StoreEnforcementState.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreAdministrationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreTransactionEligibilityService.java`

**Interfaces:**
- Produces: `StoreBaseSettings(OperationStatus operationStatus, boolean reservationEnabled, boolean menuHoldEnabled, boolean pickupEnabled)`.
- Produces: `StoreAdministrationService.recomposeAfterOperatorUpdate(long storeId, StoreBaseSettings base)` participating in the caller transaction.
- Produces: `StoreAdministrationService.requireFeatureAllowedForUpdate(long storeId, RestrictedFeature feature)` using the locked Store-scoped enforcement row.

- [ ] **Step 1: Write failing base/effective tests**

Add a Store update test whose active RESERVATION restriction remains effective after an operator requests `reservationEnabled=true`, then release it and assert the latest base value becomes effective. Add unit assertions that RESERVATION, MENU_HOLD, PICKUP, and WAITING each invoke the matching feature gate.

```java
then(storeAdministrationService).should()
        .recomposeAfterOperatorUpdate(STORE_ID,
                new StoreBaseSettings(OperationStatus.OPEN, true, true, true));
```

- [ ] **Step 2: Run RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.service.StoreServiceTest" --tests "com.miriyum.domain.store.service.StoreTransactionEligibilityServiceTest" --tests "com.miriyum.domain.store.service.StoreAdministrationServiceIT"
```

Expected: missing base synchronization and missing feature checks.

- [ ] **Step 3: Implement base synchronization**

Inject `StoreAdministrationService` into `StoreService`. Immediately after `Store.update(...)`, pass the updated operator settings to `recomposeAfterOperatorUpdate`. When an enforcement state exists, update its base fields and reapply active projections to the managed Store; when none exists, leave the normal Store update unchanged. Do not increment `enforcementVersion` for a base-only operator update.

- [ ] **Step 4: Implement central transaction checks**

After locking the Store and validating OPEN/APPROVED, invoke the matching active restriction check:

```java
storeAdministrationService.requireFeatureAllowedForUpdate(storeId, RestrictedFeature.RESERVATION);
storeAdministrationService.requireFeatureAllowedForUpdate(storeId, RestrictedFeature.MENU_HOLD);
storeAdministrationService.requireFeatureAllowedForUpdate(storeId, RestrictedFeature.PICKUP);
storeAdministrationService.requireFeatureAllowedForUpdate(storeId, RestrictedFeature.WAITING);
```

Each public eligibility method calls only its own required feature. Preserve the existing Store mode checks and purpose-specific DTOs.

- [ ] **Step 5: Run GREEN**

Repeat the Task 3 command. Expected: all selected tests pass.

- [ ] **Step 6: Commit the Store-owned contract-first change**

Commit message:

```text
fix: separate store base and enforcement modes
```

### Task 4: Route permanent exit through an OPER-009 Store command

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreAdministrationServiceIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCommandServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionConcurrencyIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionMigrationIT.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/dto/administration/StoreAdministrationContracts.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/entity/StoreEnforcementState.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreAdministrationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/model/StoreSanctionPolicyCatalog.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCommandService.java`
- Modify: `backend/src/main/resources/db/migration/V53__create_store_sanctions.sql`

**Interfaces:**
- Produces: `PermanentClosureCause.PLATFORM_SANCTION`.
- Produces: `PermanentClosureCommand(long storeId, long expectedEnforcementVersion, long sanctionId, long approvalId, PermanentClosureCause cause, String policyVersion)`.
- Produces: `StoreAdministrationService.closePermanently(PermanentClosureCommand)` returning `EnforcementResult`.

- [ ] **Step 1: Write failing Store closure tests**

Assert that closure records sanction ID, approval ID, cause, and policy version once; increments enforcement version once; closes only the target Store; and rejects generic release of the bound sanction.

- [ ] **Step 2: Write failing admin-store orchestration tests**

For PERMANENT_EXIT approval, capture the command sent to `closePermanently` and assert that it uses the persisted approval ID plus `StoreSanctionCase.policyVersion`. For PERMANENT_EXIT release, assert `AdminStoreErrorCode.POLICY_VIOLATION` and no Store release call.

- [ ] **Step 3: Run RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.service.StoreAdministrationServiceIT" --tests "com.miriyum.domain.platformoperator.adminstore.service.StoreSanctionCommandServiceTest"
```

Expected: permanent closure port and release guard do not exist.

- [ ] **Step 4: Extend V53 and the Store state**

Add nullable closure binding columns to `store_enforcement_states` and constraints after approval table creation:

```sql
permanent_closure_sanction_id BIGINT NULL,
permanent_closure_approval_id BIGINT NULL,
permanent_closure_cause VARCHAR(40) NULL,
permanent_closure_policy_version VARCHAR(50) NULL
```

Add an all-null-or-all-present CHECK, a unique sanction binding, and RESTRICT foreign keys to `store_sanctions` and `store_sanction_approvals`. Map the fields without a public mutator other than the one-time closure transition.

- [ ] **Step 5: Implement conditional closure and orchestration**

Persist and flush `StoreSanctionApproval` before the Store command, then call `closePermanently`. The Store port locks Store and enforcement state, verifies expected version and absent closure binding, records the immutable facts, increments version, and calls `Store.close()`. Keep generic projection apply unavailable for PERMANENT_EXIT and reject its generic release before calling `stores.release`.

- [ ] **Step 6: Run GREEN and targeted MySQL tests**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.service.StoreAdministrationServiceIT" --tests "com.miriyum.domain.platformoperator.adminstore.service.StoreSanctionCommandServiceTest"
.\gradlew.bat integrationTest --tests "com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionMigrationIT" --tests "com.miriyum.domain.platformoperator.adminstore.service.StoreSanctionConcurrencyIT"
```

Expected: unit and the two affected MySQL classes pass; no broad integration suite runs.

- [ ] **Step 7: Commit the permanent closure boundary**

Commit message:

```text
fix: bind permanent exit to store closure policy
```

### Task 5: Final targeted verification, publication, and review replies

**Files:**
- Verify all modified allowlisted paths.
- Update GitHub PR #382 review threads and top-level status comment.

**Interfaces:**
- Consumes: exact local HEAD and GitHub PR review thread IDs.
- Produces: pushed feature HEAD, inline technical replies, resolved addressed threads, and exact-head CI evidence.

- [ ] **Step 1: Run targeted regression and contract checks**

Run only the selected test classes from Tasks 2–4 plus the affected controller/OpenAPI tests:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.platformoperator.adminstore.*" --tests "com.miriyum.domain.store.service.StoreServiceTest" --tests "com.miriyum.domain.store.service.StoreTransactionEligibilityServiceTest"
.\gradlew.bat integrationTest --tests "com.miriyum.domain.platformoperator.adminstore.controller.AdminStoreHttpIT" --tests "com.miriyum.domain.platformoperator.adminstore.repository.StoreSanctionMigrationIT" --tests "com.miriyum.domain.platformoperator.adminstore.service.StoreSanctionConcurrencyIT"
.\gradlew.bat test --tests "com.miriyum.domain.platformoperator.PlatformOperatorOpenApiContractTest"
npx redocly lint docs/specs/admin-store/openapi.yaml docs/specs/platform-operator-openapi.yaml
```

If the wildcard unit selector expands too broadly, replace it with the exact unit classes changed in Tasks 2–4.

- [ ] **Step 2: Verify repository and allowlist integrity**

Run:

```powershell
git diff --check
git status --short
git diff --name-only origin/dev...HEAD
```

Confirm `membersupport/**` is absent and every changed path appears in the active exact allowlist.

- [ ] **Step 3: Commit remaining docs/test adjustments and push only the feature branch**

Push `feature/279-admin-store-sanctions`; do not push `dev`.

- [ ] **Step 4: Reply inline and resolve addressed threads**

Reply in each of the four existing review threads with the exact commit, behavior, and targeted test evidence. Resolve a thread only after its fix is present on the pushed HEAD. Update the existing consolidated PR comment instead of creating duplicate status comments.

- [ ] **Step 5: Verify GitHub CI on the exact pushed HEAD**

Use `gh pr checks 382 --watch` in bounded polls. Report local targeted results and GitHub full-suite status separately. If CI is pending, do not claim full verification.
