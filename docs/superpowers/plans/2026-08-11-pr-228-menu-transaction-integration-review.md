# PR #228 Menu Transaction Integration Review Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Align recommendation ownership with Issue #82 and prove the real `MenuTransactionService` transaction and MySQL lock contract.

**Architecture:** The active architecture document names `recommendation` as the future top-level MVP2 domain while `search.geo` retains only MVP1 geographic filtering. A dedicated Spring Boot/Testcontainers integration test autowires the production `MenuTransactionService`, creates real Store and Menu rows, and observes transaction propagation, row-lock ordering, and rollback without service mocks.

**Tech Stack:** Java 21, Spring Boot 4.1, JUnit 5, AssertJ, Spring `TransactionTemplate`, Testcontainers MySQL 8.0.40, Gradle 9 wrapper

## Global Constraints

- Do not add a test-only production service or change production behavior.
- Do not change HTTP, JSON, Security, OpenAPI, Flyway, or database schema.
- Store row locking must precede Menu row locking.
- Tests must use the actual `MenuTransactionService`, `StoreTransactionEligibilityService`, `StoreRepository`, and `MenuRepository` beans.
- The future recommendation scorer belongs to top-level `recommendation`; only geographic search filtering belongs to `search.geo`.

---

### Task 1: Align recommendation ownership documentation

**Files:**
- Modify: `docs/06-system-architecture.md`
- Modify: `docs/superpowers/specs/2026-08-10-issue-82-domain-packages-and-api-paths-design.md`

**Interfaces:**
- Consumes: Issue #82 package ownership decision
- Produces: one consistent ownership statement across active architecture and the implementation design

- [ ] **Step 1: Replace `search.recommendation` with top-level `recommendation`**

State explicitly that `search.geo` owns only MVP1 geographic filtering and MVP2 scoring, ordering, and history policies belong to top-level `recommendation`.

- [ ] **Step 2: Record the real Bean integration-test contract**

Document actual Spring Bean usage, `MANDATORY`, Store-before-Menu row locking, caller rollback, and the prohibition on mock-only lock-order verification.

- [ ] **Step 3: Verify documentation consistency**

Run: `rg -n "search\\.recommendation|store\\.recommendation" docs ai -g "*.md"`

Expected: no future recommendation ownership remains under Store or Search; the historical source path `domain.store.recommendation.geo` may remain only in migration mappings.

### Task 2: Add real Menu transaction integration coverage

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/menu/service/MenuTransactionServiceIT.java`

**Interfaces:**
- Consumes: `MenuTransactionService.requireTransactionEligibility(long, long)`
- Produces: Testcontainers coverage for actual Spring transaction propagation, MySQL row-lock order, and rollback

- [ ] **Step 1: Create real Store and published Menu fixtures**

Use repositories and domain factories inside `TransactionTemplate`; clean Menu version tables, menus, Stores, and operator accounts in foreign-key order.

- [ ] **Step 2: Add a no-transaction `MANDATORY` test**

Call the autowired service outside `TransactionTemplate` and expect `IllegalTransactionStateException`.

- [ ] **Step 3: Add an active-transaction snapshot test**

Call the actual service inside `TransactionTemplate` and compare the returned `MenuTransactionEligibility` with literal Store/Menu values.

- [ ] **Step 4: Add a Store-before-Menu row-lock test**

Hold the Menu row in transaction A. Invoke the service in transaction B and wait until MySQL reports B blocked on `menus.PRIMARY`. Start transaction C against the Store row and assert it blocks, proving B acquired Store before waiting on Menu. Release A and assert all transactions complete.

- [ ] **Step 5: Add caller rollback coverage**

Call the service and mutate the Store in one `TransactionTemplate`, throw a sentinel exception, and assert the Store remains OPEN afterward.

- [ ] **Step 6: Verify test efficacy with mutations**

Temporarily change `MANDATORY` to `REQUIRED` and confirm the no-transaction test fails. Restore it. Temporarily reverse Store/Menu lookup order and confirm the lock-order test fails. Restore production code.

- [ ] **Step 7: Run focused and full verification**

Run: `backend/gradlew.bat integrationTest --tests com.miriyum.domain.menu.service.MenuTransactionServiceIT --console=plain`

Expected: PASS when Docker is available.

Run: `backend/gradlew.bat test --console=plain`

Expected: all unit tests PASS with no skipped tests.

Run: `git diff --check`

Expected: exit code 0.

### Task 3: Publish evidence and close the new review request

**Files:**
- Modify only the three files listed above if verification exposes an issue

**Interfaces:**
- Consumes: verified commit and PR #228 top-level review comment
- Produces: pushed branch and evidence-backed GitHub completion reply

- [ ] **Step 1: Commit and push**

```text
git add docs/06-system-architecture.md docs/superpowers/specs/2026-08-10-issue-82-domain-packages-and-api-paths-design.md docs/superpowers/plans/2026-08-11-pr-228-menu-transaction-integration-review.md backend/src/test/java/com/miriyum/domain/menu/service/MenuTransactionServiceIT.java
git commit -m "test: verify menu transaction lock contract"
git push origin codex/issue-82-design
```

- [ ] **Step 2: Reply to the latest PR conversation comment**

State that recommendation ownership is aligned to Issue #82, name the actual production Bean and MySQL behaviors verified, and include local/GitHub CI evidence. Do not claim Testcontainers success unless the corresponding run completed successfully.
