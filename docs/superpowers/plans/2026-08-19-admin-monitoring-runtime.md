# Admin Monitoring Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 공개 원장 계약만 조합해 예약·웨이팅 사건 목록과 최소 공개 상세를 동일 asOf, signed cursor, 원장별 부분 실패로 제공한다.

**Architecture:** Controller는 HTTP binding만 담당하고 authorization, cursor, status mapping, source orchestration을 작은 서비스로 분리한다. Query service는 Reservation/Waiting source-specific seek를 k-way merge하고 공개 batch/detail DTO를 조합하며, 장애는 source 단위 failure와 nullable state로 격리한다.

**Tech Stack:** Java 21, Spring Boot MVC/Security, JUnit 5, Mockito, MockMvc, Jackson, HMAC-SHA256, OpenAPI 3.1

**Spec:** `docs/superpowers/specs/2026-08-19-admin-monitoring-design.md`

## Global Constraints

- `origin/dev`의 #472 공개 Service/DTO만 사용하고 원 도메인 Entity·Repository를 import하지 않는다.
- `changedFrom`/`changedTo`는 필수, 최대 31일이고 size는 1..100이다.
- 모든 source 호출은 요청당 동일 asOf를 사용한다.
- 한 source 실패가 다른 source의 state, lifecycle, reconciliation을 덮어쓰지 않는다.
- detail은 `OPERATIONS_MONITOR_READ`와 활성 `OPERATIONS_MONITORING` assignment를 모두 요구한다.
- 민감 원문·내부 사용자 ID·provider 식별자를 응답에 포함하지 않는다.
- snapshot/projection/migration을 추가하지 않는다.
- 전체 backend suite를 로컬에서 실행하지 않는다.

---

### Task 1: HTTP 요청 모델, 오류 계약, 응답 모델

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/dto/AdminMonitoringRequests.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/dto/AdminMonitoringResponses.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/exception/AdminMonitoringErrorCode.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringStatusMapperTest.java`

**Interfaces:**
- Produces: `CaseType`, `LifecycleStatus`, `Source`, `Completeness`, `ReconciliationStatus`, normalized `ListQuery`, `CasePage`, `CaseDetail`, `LedgerCell`, `DependencyFailure` and ServiceException error codes.

- [ ] **Step 1: Write failing DTO validation tests** for invalid range, size, case prefix/type mismatch, unqualified source status and duplicate values.
- [ ] **Step 2: Run** `./gradlew test --tests '*AdminMonitoringStatusMapperTest'` from `backend` and confirm compilation/test failure because DTOs do not exist.
- [ ] **Step 3: Implement immutable records/enums** with copied collections, canonical sorted filters, 31-day validation and errors `INVALID_MONITORING_FILTER`, `INVALID_CURSOR`, `EXPIRED_CURSOR`, `MONITORING_CASE_NOT_FOUND`, `MONITORING_SOURCES_UNAVAILABLE`.
- [ ] **Step 4: Re-run the targeted test** and confirm DTO validation passes.
- [ ] **Step 5: Commit** DTO/error groundwork with its test.

### Task 2: Status mapping

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringStatusMapper.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringStatusMapperTest.java`

**Interfaces:**
- Consumes: source name/status and Reservation event type.
- Produces: `LifecycleStatus lifecycle(Source, String)` and conversions for completeness/reconciliation plus primary ledger selection.

- [ ] **Step 1: Add table-driven failing tests** with literal expected lifecycle values for every approved ReservationHold, Reservation and Waiting status, including CHECKED_IN event precedence and reconciliation independence.
- [ ] **Step 2: Run the mapper test** and confirm the missing mapper/branch failures.
- [ ] **Step 3: Implement exhaustive switch mappings** and reject unknown source/status with `INVALID_MONITORING_FILTER`; never derive lifecycle from MenuHold or Payment.
- [ ] **Step 4: Re-run the mapper test** and confirm all cases pass.
- [ ] **Step 5: Commit** status mapping.

### Task 3: Signed cursor and filter fingerprint

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringCursorCodec.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringCursorCodecTest.java`

**Interfaces:**
- Consumes: normalized `ListQuery`, asOf, global seek, Reservation seek and Waiting seek.
- Produces: `String encode(CursorState, ListQuery)` and `CursorState decode(String, ListQuery)`.

- [ ] **Step 1: Write failing tests** for round-trip, tampering, changed filter/size, unknown key, 30-minute expiry, future issuedAt and stable canonical fingerprint.
- [ ] **Step 2: Run** `./gradlew test --tests '*AdminMonitoringCursorCodecTest'` and confirm failure because codec is absent.
- [ ] **Step 3: Implement** base64url JSON payload plus constant-time HMAC-SHA256 verification, `contractVersion=1`, configurable active key ID/secret and Clock.
- [ ] **Step 4: Re-run the cursor test** and confirm INVALID_CURSOR versus EXPIRED_CURSOR branches pass.
- [ ] **Step 5: Commit** cursor codec.

### Task 4: Current authority and detail assignment

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringAuthorizationService.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringAuthorizationServiceTest.java`

**Interfaces:**
- Consumes: `PlatformOperatorPrincipal`, `OperatorAuthorityReader`, `AdminCaseAssignmentVerifier`.
- Produces: `requireRead(principal)` and `requireDetail(principal, caseId, caseVersion)`.

- [ ] **Step 1: Write failing behavior tests** for current authority version, missing permission, correct permission and exact OPERATIONS_MONITORING assignment request.
- [ ] **Step 2: Run the authorization test** and confirm missing class failure.
- [ ] **Step 3: Implement permission check** and assignment verification; reject with existing `ADMIN_001` without adding reauthentication or audit writes.
- [ ] **Step 4: Re-run the authorization test** and confirm all branches pass.
- [ ] **Step 5: Commit** authorization boundary.

### Task 5: Source orchestration, merge, filters and partial failure

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringQueryService.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/service/AdminMonitoringQueryServiceTest.java`

**Interfaces:**
- Consumes: four public monitoring query services, mapper, cursor, authorization and Clock.
- Produces: `CasePage list(PlatformOperatorPrincipal, ListQuery)` and `CaseDetail get(PlatformOperatorPrincipal, CaseType, String, Instant)`.

- [ ] **Step 1: Write failing list tests** proving global tie order, source-specific seek continuation, same asOf on all calls, one-base failure PARTIAL/null cursor, both-base failure 503, ancillary failure isolation, delayed preservation and filters after hydration.
- [ ] **Step 2: Run the query service test** and confirm the missing service failure.
- [ ] **Step 3: Implement the minimal list pipeline**: decode/establish asOf, fetch one bounded public change page per requested base source, k-way merge, hydrate batches of at most 100, evaluate filters, advance only evaluated source checkpoints and encode next cursor only when ordering is trustworthy.
- [ ] **Step 4: Add failing detail tests** for Reservation and Waiting success, check-in/no-show history, primary unavailable 503, successful absence 404, ancillary PARTIAL and payment ledger/refund truncation preservation.
- [ ] **Step 5: Implement detail composition** and call `requireDetail` only after the primary source yields the current caseVersion.
- [ ] **Step 6: Re-run the query service test** and confirm all list/detail cases pass.
- [ ] **Step 7: Commit** orchestration.

### Task 6: Conditional HTTP API and security behavior

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/controller/management/adminmonitoring/AdminMonitoringController.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/controller/management/adminmonitoring/AdminMonitoringControllerTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminmonitoring/AdminMonitoringApiIT.java`

**Interfaces:**
- Consumes: query service and `@AuthenticationPrincipal PlatformOperatorPrincipal`.
- Produces: the two approved GET routes wrapped in `ApiResponse`.

- [ ] **Step 1: Write failing MockMvc tests** for request binding, success envelopes, invalid filter 400 and feature OFF 404.
- [ ] **Step 2: Run the controller test** and confirm route absence.
- [ ] **Step 3: Implement the controller** under `controller.management.adminmonitoring`, conditional on both feature flags, with no business logic.
- [ ] **Step 4: Write failing focused HTTP integration tests** for missing JWT 401, stale authority 401, permission 403, assignment 403 and assigned detail 200.
- [ ] **Step 5: Add only the test fixtures needed** inside the allowlisted IT and make the focused integration test pass.
- [ ] **Step 6: Run controller and API IT only** and confirm pass.
- [ ] **Step 7: Commit** HTTP runtime.

### Task 7: Activate feature OpenAPI and enforce boundaries

**Files:**
- Modify: `docs/specs/admin-monitoring/spec.md`
- Modify: `docs/specs/admin-monitoring/openapi.yaml`
- Modify only if required after rebase: `docs/specs/platform-operator-openapi.yaml`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/AdminMonitoringOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`

**Interfaces:**
- Produces: runtime-active OpenAPI paths matching Java DTO fields and an architecture assertion that adminmonitoring imports no foreign Entity/Repository.

- [ ] **Step 1: Write failing OpenAPI tests** that load both feature and aggregate documents, require both operations, reject `contract-only`, and assert 200/400/401/403/404/503 schemas.
- [ ] **Step 2: Add a failing architecture test** that scans adminmonitoring imports and rejects `.entity.` and `.repository.` outside platformoperator.
- [ ] **Step 3: Run both targeted tests** and confirm expected failures.
- [ ] **Step 4: Remove contract-only prose/markers and align schemas** with the implemented DTOs; retain the existing aggregate refs and preserve #281's additive refs when rebasing.
- [ ] **Step 5: Re-run OpenAPI and architecture tests** and confirm pass.
- [ ] **Step 6: Commit** spec activation and boundary guards.

### Task 8: Targeted regression, allowlist audit and publication

**Files:**
- All files in Issue #280 exact allowlist only.

**Interfaces:**
- Produces: verified branch and Draft PR targeting dev.

- [ ] **Step 1: Run targeted unit tests** for admin monitoring DTO/status/cursor/authorization/query/controller.
- [ ] **Step 2: Run only `AdminMonitoringApiIT`, `AdminMonitoringOpenApiContractTest`, source public contract tests touched by consumption, and `DomainPackageArchitectureTest`; do not run build/check/full integration/all shards.**
- [ ] **Step 3: Run** `git diff --check`, compare `git diff --name-only origin/dev...HEAD` to the Issue #280 allowlist, and scan response DTOs for forbidden identity/provider fields.
- [ ] **Step 4: Fetch/rebase latest origin/dev**, resolving the shared aggregate OpenAPI additively without losing #281 paths; rerun the same targeted tests.
- [ ] **Step 5: Commit any final docs/test adjustments, push `feature/280-admin-monitoring`, and open a Draft PR to dev.**
- [ ] **Step 6: Report local targeted evidence and leave full regression status to GitHub CI.**
