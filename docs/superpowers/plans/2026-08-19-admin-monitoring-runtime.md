# Admin Monitoring Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Implement the platform-operator reservation/waiting monitoring list and detail APIs over the merged public source contracts, with truthful partial failure, secure opaque cursors, assignment-aware detail access, and permanently masked data.

**Architecture:** A platform-owned live federation service requests a single `asOf`, asks all four public source services for changed case candidates, merge-sorts/deduplicates them, batch-fills source cells, maps lifecycle/reconciliation independently, and emits an API envelope. It never imports source Entities/Repositories and creates no database projection.

**Tech Stack:** Java 21, Spring Boot MVC/Security, Jackson, HMAC-SHA256, JUnit 5, Mockito, MockMvc, ArchUnit, Gradle.

**Spec:** `docs/superpowers/specs/2026-08-19-admin-monitoring-design.md`

## Global Constraints

- Start only after the source-contract PR is merged and CI-green. Fetch latest `origin/dev`, then create a new isolated worktree and branch `feature/280-admin-monitoring` from that exact commit.
- No migration and no admin snapshot. Reconfirm V62 and all open migrations only to document non-conflict.
- Import only the four public monitoring Service/DTO contracts. No source Entity/Repository references, including tests that accidentally make the runtime depend on internals.
- A failed ledger cannot overwrite another ledger's state. Delayed/unavailable cells cannot be mapped as current/confirmed.
- List requires `OPERATIONS_MONITOR_READ`; detail additionally verifies active `OPERATIONS_MONITORING` assignment. JWT/current authority is authoritative; no password reauthentication.
- All subjects and payment/contact references stay minimized/masked. There is no raw reveal parameter or endpoint and no separate business audit row for reads.
- A partial list has HTTP 200, `completeness=PARTIAL`, explicit failures, and `nextCursor=null`. Both primary candidate sources failed means 503. Detail primary failure means 503; successful absence means 404; child failure means HTTP 200 partial.
- Use only the exact runtime allowlist recorded on issue #280; amend it before editing any additional path.
- Run named unit tests and affected integration tests only. GitHub CI is the full-suite authority.

## Runtime Types and Signatures

`AdminMonitoringResponses` owns API enums `CaseType`, `LifecycleStatus`, `Completeness`, `ReconciliationStatus`, and records `SourceMetadata`, `LedgerCell`, `DependencyFailure`, `CaseSummary`, `CasePage`, and `CaseDetail`. A `CaseSummary` has positive `caseVersion`, while each `LedgerCell.statusVersion` preserves the source's raw non-negative version.

```java
CasePage list(AdminMonitoringRequests.ListRequest request, OperatorContext operator);
CaseDetail detail(String caseId, Instant asOf, OperatorContext operator);

void requireList(long operatorId, long expectedAuthorityVersion);
void requireDetail(long operatorId, long expectedAuthorityVersion,
                   String caseId, long caseVersion);
```

The fixed seek key is `(statusChangedAt DESC, caseType ASC, caseId DESC)`. The cursor payload is versioned and signed:

```java
record CursorPayload(int contractVersion, String keyId, Instant asOf,
                     String filterFingerprint, Instant statusChangedAt,
                     CaseType caseType, String caseId) {}
```

Cursor decode distinguishes malformed/tampered (`400 INVALID_CURSOR`) from an unknown retired `keyId` (`400 EXPIRED_CURSOR`). The codec receives a minimum-32-byte secret through `@Value("${miriyum.platform-operator.admin-monitoring.cursor-secret:}")` and derives a non-secret key ID; secrets are never serialized or logged.

---

### Task 1: Freeze HTTP DTO and lifecycle mapping

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/dto/AdminMonitoringRequests.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/dto/AdminMonitoringResponses.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringStatusMapper.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringStatusMapperTest.java`

- [ ] Write DTO validation tests for required range, `changedFrom <= changedTo`, maximum 31 days, size 1..100/default 20, string IDs, positive case version, raw non-negative source versions, sorted history, and permanently masked fields.
- [ ] Write an explicit table test for every merged Reservation, MenuHold, Payment, and Waiting status. Assert unknown/new source status raises a source-specific mapping failure instead of inventing a lifecycle.
- [ ] Run both named tests and confirm RED.
- [ ] Implement records and the exhaustive mapper. Keep lifecycle and reconciliation as separate fields; compute case `statusChangedAt` as the maximum successful linked state time.
- [ ] Re-run both tests and confirm GREEN.
- [ ] Commit: `feat(admin-monitoring): define response and status contracts`.

### Task 2: Implement the signed cursor contract

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringCursorCodec.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringCursorCodecTest.java`

- [ ] Write tests for round-trip, fixed canonical filter fingerprint, HMAC tampering, wrong filters, changed sort tuple, malformed payload, secret shorter than 32 bytes, and retired/unknown key ID.
- [ ] Run the named test and confirm RED.
- [ ] Implement URL-safe Base64 JSON payload plus HMAC-SHA256, constant-time signature comparison, canonical sorted filter serialization, contract version validation, and safe errors without cursor content.
- [ ] Re-run the named test and confirm GREEN.
- [ ] Commit: `feat(admin-monitoring): add opaque signed cursor`.

### Task 3: Enforce authority, assignment, and masking

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringAuthorizationService.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringAuthorizationServiceTest.java`

- [ ] Write tests proving list calls `OperatorAuthorityReader.requireCurrentAuthority`, rejects stale authority/JWT operator mismatch, and requires `OPERATIONS_MONITOR_READ`.
- [ ] Add detail tests proving `AdminCaseAssignmentVerifier.verify(new AdminCaseAssignmentRequest(OPERATIONS_MONITORING, caseId, caseVersion, operatorId))` is required after permission and that source version 0 becomes assignment case version 1.
- [ ] Assert no password/reauth service and no audit writer is a dependency; masked response types have no raw fields.
- [ ] Run the named test and confirm RED.
- [ ] Implement the service using only existing authority/assignment contracts and existing HTTP exception semantics.
- [ ] Re-run the named test and confirm GREEN.
- [ ] Commit: `feat(admin-monitoring): enforce read and case assignment access`.

### Task 4: Federate source candidates and partial failures

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringQueryService.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringQueryServiceTest.java`

- [ ] Write tests that one `asOf` is passed unchanged to every source, candidates from all four sources are merge-sorted and deduplicated by stable case ID, and linked source cells are batch loaded without per-row calls.
- [ ] Add cursor tests proving the seek tuple is the last evaluated candidate, not the last returned item, and only fully ordered complete pages receive a cursor.
- [ ] Add failure matrix tests: one primary source failed => 200 partial/no cursor; both primary sources failed => 503; child source failed => unaffected lifecycle retained plus failure; delayed cell => not current; detail primary failed => 503; detail absent => 404; child failed => partial detail.
- [ ] Add filter tests for store, case type, lifecycle, source-qualified status, reconciliation, and time window, including filter fingerprint mismatch.
- [ ] Run the named test and confirm RED.
- [ ] Implement bounded over-fetch/merge, batch fill, explicit per-source exception isolation, completeness aggregation, fixed sorting, and masking. Never catch a source error as an empty result.
- [ ] Re-run the named test and confirm GREEN.
- [ ] Commit: `feat(admin-monitoring): federate ledgers with partial failure`.

### Task 5: Expose list/detail HTTP APIs and OpenAPI

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/controller/AdminMonitoringController.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/controller/AdminMonitoringControllerTest.java`
- Modify: `docs/specs/admin-monitoring/openapi.yaml`
- Modify: `docs/specs/platform-operator-openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/AdminMonitoringOpenApiContractTest.java`

- [ ] Write MockMvc tests for `GET /api/v1/platform-operators/admin-monitoring/cases` and `GET /api/v1/platform-operators/admin-monitoring/cases/{caseType}/{caseId}` with JWT principal/authority version propagation and `ApiResponse<T>`.
- [ ] Cover 200 complete/partial, 400 validation/invalid or expired cursor, 401 unauthenticated, 403 permission or assignment, 404 feature-off or absent detail, and 503 source-unavailable matrices.
- [ ] Run controller and OpenAPI contract tests and confirm RED.
- [ ] Implement the controller with the repository's existing platform-operator feature-off routing convention; activate final schemas/examples in feature and aggregate OpenAPI.
- [ ] Re-run both tests and confirm GREEN.
- [ ] Commit: `feat(admin-monitoring): expose platform monitoring APIs`.

### Task 6: Verify boundary, targeted integration, and publish

**Files:**
- Modify: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/AdminMonitoringApiIT.java`

- [ ] Extend the architecture test to reject `..entity..` and `..repository..` dependencies from `platformoperator.adminmonitoring` and allow only the public source contracts/services.
- [ ] Write one integration test for a mixed Reservation/Waiting page, linked MenuHold/Payment cells, assignment-protected detail, fixed `asOf/dataThrough`, masking, and a simulated child-source partial response.
- [ ] Run only `AdminMonitoringStatusMapperTest`, `AdminMonitoringCursorCodecTest`, `AdminMonitoringAuthorizationServiceTest`, `AdminMonitoringQueryServiceTest`, `AdminMonitoringControllerTest`, `AdminMonitoringOpenApiContractTest`, `DomainPackageArchitectureTest`, and `AdminMonitoringApiIT`.
- [ ] Run `git diff --check`; compare `git diff --name-only origin/dev...HEAD` byte-for-byte with the issue #280 runtime allowlist. Amend the issue before committing any discrepancy.
- [ ] Commit: `test(admin-monitoring): verify integration and domain boundary`.
- [ ] Push only `feature/280-admin-monitoring`, open a draft PR to `dev`, link #280 and the prerequisite PR, and state that full backend verification is pending/authoritative in GitHub CI.

## Completion Gate

Completion means targeted local evidence is green, draft PR CI has started, no direct push to `dev` occurred, the current user workspace is unchanged, and any full-suite result is reported only from GitHub CI.
