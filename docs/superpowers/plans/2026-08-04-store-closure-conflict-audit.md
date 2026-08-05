# Store Closure Conflict Audit Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Persist explicit `NOT_EVALUATED` conflict-check metadata with an unknown conflict count for every Store closure audit event in PR #106.

**Architecture:** Keep conflict metadata centralized in `StoreClosureAuditEvent.record(...)`, reusing the Store schedule `ConflictCheckStatus` enum. Enforce the same state/count invariant in the unmerged V18 clean-start schema and cover every closure lifecycle audit path through service tests.

**Tech Stack:** Java 21, Spring Boot 4.1.0, JPA/Hibernate, MySQL 8/Flyway, JUnit 5, AssertJ, Mockito, Gradle 9.6.1

## Global Constraints

- Before Issue #69 is connected, every closure audit event records `ConflictCheckStatus.NOT_EVALUATED` and `conflictCount == null`.
- Do not query Reservation or other domains and do not infer a zero conflict count.
- Modify the PR #106 V18 migration directly because it is not merged or deployed; do not add V19.
- Do not change public HTTP routes, DTOs, OpenAPI, error codes, or policy meaning.
- Preserve the current actor, idempotency, transaction, and activation-failure behavior.

---

### Task 1: Drive audit metadata through every command path

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/closure/service/StoreClosureServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/closure/service/TemporaryClosureServiceTest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/closure/entity/StoreClosureAuditEvent.java`

**Interfaces:**
- Consumes: existing `StoreClosureAuditEvent.record(...)`, `StoreClosureService`, `TemporaryClosureService`, and `ConflictCheckStatus`
- Produces: `StoreClosureAuditEvent#getConflictCheckStatus(): ConflictCheckStatus` and `StoreClosureAuditEvent#getConflictCount(): Integer`

- [ ] **Step 1: Write failing regular-closure audit assertions**

Capture the saved `StoreClosureAuditEvent` for draft creation, immediate and scheduled publication, publication cancellation, scheduled activation, and activation failure. For each event assert the action plus literal metadata:

```java
assertThat(event.getConflictCheckStatus()).isEqualTo(ConflictCheckStatus.NOT_EVALUATED);
assertThat(event.getConflictCount()).isNull();
```

Add the missing successful scheduled publication, publication cancellation, and activation-failure scenarios using the fixed `CLOCK`, real closure entities, and only repository/idempotency mocks at the persistence boundary.

- [ ] **Step 2: Write failing temporary-closure audit assertions**

Extend creation and end-change tests and add a scheduled cancellation test. Capture the real event passed to the audit repository and assert `CREATED`, `END_CHANGED`, or `CANCELLED` together with `NOT_EVALUATED` and null count.

- [ ] **Step 3: Run the focused tests and verify RED**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.closure.service.StoreClosureServiceTest" `
  --tests "com.miriyum.domain.store.closure.service.TemporaryClosureServiceTest" `
  --no-daemon --max-workers=1
```

Expected: test compilation fails because `getConflictCheckStatus()` and `getConflictCount()` do not exist. This is the intended failure caused by missing audit metadata.

- [ ] **Step 4: Implement the minimal entity fields and factory defaults**

Add these fields to `StoreClosureAuditEvent`:

```java
@Enumerated(EnumType.STRING)
@Column(name = "conflict_check_status", nullable = false, length = 20)
private ConflictCheckStatus conflictCheckStatus;

@Column(name = "conflict_count")
private Integer conflictCount;
```

Import the existing `com.miriyum.domain.store.schedule.model.ConflictCheckStatus` and set only this value in `record(...)`:

```java
event.conflictCheckStatus = ConflictCheckStatus.NOT_EVALUATED;
```

Leave `conflictCount` null and do not add caller parameters that could claim an evaluated result.

- [ ] **Step 5: Run the focused tests and verify GREEN**

Run the Step 3 command again. Expected: both service test classes pass with every audited lifecycle path preserving unknown conflict information.

- [ ] **Step 6: Commit the behavior change**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/closure/entity/StoreClosureAuditEvent.java backend/src/test/java/com/miriyum/domain/store/closure/service/StoreClosureServiceTest.java backend/src/test/java/com/miriyum/domain/store/closure/service/TemporaryClosureServiceTest.java
git commit -m "fix: preserve closure conflict audit status"
```

### Task 2: Enforce the metadata invariant in V18

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/store/closure/repository/StoreClosureMigrationContractTest.java`
- Modify: `backend/src/main/resources/db/migration/V18__create_store_closures.sql`

**Interfaces:**
- Consumes: `StoreClosureAuditEvent` column mapping from Task 1
- Produces: MySQL columns `conflict_check_status`, `conflict_count` and DB invariant checks

- [ ] **Step 1: Write the failing migration contract assertion**

Extend the migration test to require the two columns and both checks:

```java
assertThat(sql).contains(
        "conflict_check_status VARCHAR(20) NOT NULL",
        "conflict_count INT NULL",
        "CHECK (conflict_check_status IN ('NOT_EVALUATED', 'EVALUATED'))",
        "conflict_check_status = 'NOT_EVALUATED'",
        "conflict_count IS NULL",
        "conflict_check_status = 'EVALUATED'",
        "conflict_count >= 0");
```

- [ ] **Step 2: Run the migration contract test and verify RED**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.closure.repository.StoreClosureMigrationContractTest" --no-daemon --max-workers=1
```

Expected: FAIL because V18 does not contain the conflict metadata columns or constraints.

- [ ] **Step 3: Add the V18 columns and constraints**

Add `conflict_check_status VARCHAR(20) NOT NULL` and `conflict_count INT NULL` beside the other audit outcome data. Add an enum-domain check and the coupled invariant:

```sql
CONSTRAINT ck_closure_audit_conflict_check
    CHECK (conflict_check_status IN ('NOT_EVALUATED', 'EVALUATED')),
CONSTRAINT ck_closure_audit_conflict_result
    CHECK (
        (conflict_check_status = 'NOT_EVALUATED' AND conflict_count IS NULL)
        OR
        (conflict_check_status = 'EVALUATED'
            AND conflict_count IS NOT NULL
            AND conflict_count >= 0)
    ),
```

- [ ] **Step 4: Run migration and closure tests and verify GREEN**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.closure.*" --no-daemon --max-workers=1
```

Expected: PASS.

- [ ] **Step 5: Commit the schema contract**

```powershell
git add -- backend/src/main/resources/db/migration/V18__create_store_closures.sql backend/src/test/java/com/miriyum/domain/store/closure/repository/StoreClosureMigrationContractTest.java
git commit -m "fix: enforce closure conflict audit invariant"
```

### Task 3: Verify the complete PR amendment

**Files:**
- Verify only: all files changed by Tasks 1 and 2

**Interfaces:**
- Consumes: completed entity, service tests, and V18 schema contract
- Produces: reproducible completion evidence for PR #106

- [ ] **Step 1: Run Store closure and migration-focused verification**

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.closure.*" --no-daemon --max-workers=1
```

- [ ] **Step 2: Run the complete backend suite**

```powershell
.\gradlew.bat clean test --no-daemon --max-workers=1
```

- [ ] **Step 3: Aggregate test XML results**

Read every `backend/build/test-results/test/TEST-*.xml` and report totals for tests, failures, errors, and skipped. Any non-zero failures/errors blocks completion.

- [ ] **Step 4: Run the backend build**

```powershell
.\gradlew.bat build -x test --no-daemon --max-workers=1
```

- [ ] **Step 5: Check diff integrity and scope**

```powershell
git diff --check 37fc088994d6fb97dfc2ab2f465a942a43256098...HEAD
git diff --name-only 37fc088994d6fb97dfc2ab2f465a942a43256098...HEAD
git status --short --branch
```

Expected paths are only the approved design/plan, closure entity, V18 migration, and closure tests.

- [ ] **Step 6: Commit any verification-only plan updates if needed**

Do not change production behavior in this step. If no files changed, do not create an empty commit.
