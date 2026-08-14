# Issue #276 Admin RBAC Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Provide the shared platform-operator role/permission catalog, versioned grants, case assignment verification, current-password one-time approvals, high-risk command guard, audit context, and last-super-admin protection required by Issues #277–#282.

**Architecture:** Extend the existing `platformoperator` domain from #275 without creating a second authentication namespace. MySQL remains the source of truth for grants, assignments, and approval consumption; every high-risk authorization joins the caller's transaction, locks the operator account, rechecks the current authority version, and consumes one approval exactly once.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Spring Security, Spring Data JPA, MySQL 8/Testcontainers, Flyway V40, Gradle 9.6.1, OpenAPI 3.1.

## Global Constraints

- Work only in `feature/276-admin-rbac` from latest `origin/dev` and within the approved file allowlist in the design.
- Preserve #275's `/api/v1/platform-operators/**` namespace, `platform-operator` JWT audience, account table, central Valkey session, `authority_version`, and `AUTH_015` invalidation contract.
- Do not implement WebAuthn, business-domain commands, operator-management HTTP APIs, audit-search APIs, frontend files, or broad PII permissions.
- Do not log or persist current passwords, approval plaintext, token plaintext, or raw session identifiers.
- All high-risk commands require current permission, current case assignment, current session/version, and an unexpired single-use approval.
- The reauthentication approval lifetime is exactly five minutes and is never extended.
- Java production behavior follows RED → GREEN → REFACTOR; time tests use `Clock`, concurrency tests use barrier/latch, and MySQL evidence uses `@Tag("integration")`.

---

### Task 1: Freeze the active spec and OpenAPI contract

**Files:**
- Create: `docs/specs/platform-operator-authorization/spec.md`
- Create: `docs/specs/platform-operator-authorization/openapi.yaml`
- Modify: `docs/specs/platform-operator-openapi.yaml`
- Modify: `docs/specs/README.md`
- Modify: `docs/02-users-and-permissions.md`
- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/07-data-and-api-contracts.md`
- Modify: `docs/09-quality-operations-and-rules.md`
- Modify: `docs/service-policies/15-admin-operation.md`
- Modify: `redocly.yaml`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorAuthorizationOpenApiContractTest.java`
- Test: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`

**Interfaces:**
- Consumes: #275 `platformOperatorBearerAuth` and `AUTH_015` session invalidation.
- Produces: `POST /api/v1/platform-operators/reauthentication-approvals`, request/response schemas, `X-Admin-Reauthentication`, `ADMIN_001`–`ADMIN_003` errors, and the exact role/permission catalog.

- [ ] **Step 1: Add the active feature spec and OpenAPI**

Write the approved design as the canonical feature spec. Define this request and response exactly:

```yaml
ReauthenticationApprovalRequest:
  type: object
  additionalProperties: false
  required: [currentPassword, purpose, targetType, targetId]
ReauthenticationApprovalData:
  type: object
  additionalProperties: false
  required: [approval, expiresAt]
```

Expose only `POST /api/v1/platform-operators/reauthentication-approvals`; do not add role-management or business-command endpoints.

- [ ] **Step 2: Write the failing OpenAPI contract test**

```java
@Test
void authorizationPathIsExposedOnlyByPlatformOperatorAudience() {
    assertThat(platformOperatorPaths()).contains("/api/v1/platform-operators/reauthentication-approvals");
    assertThat(mvp1Paths()).doesNotContain("/api/v1/platform-operators/reauthentication-approvals");
}
```

- [ ] **Step 3: Run the contract test and verify RED**

Run: `cd backend; .\gradlew.bat test --tests "*PlatformOperatorAuthorizationOpenApiContractTest"`

Expected: FAIL until the test class and audience contract are aligned with the new spec.

- [ ] **Step 4: Complete references and verify GREEN**

Run: `cd backend; .\gradlew.bat test --tests "*PlatformOperatorAuthorizationOpenApiContractTest" --tests "*AudienceOpenApiContractTest"`

Expected: PASS with the authorization path present only in the platform-operator audience.

- [ ] **Step 5: Commit the contract**

```powershell
git add -- docs/02-users-and-permissions.md docs/05-functional-requirements.md docs/07-data-and-api-contracts.md docs/09-quality-operations-and-rules.md docs/service-policies/15-admin-operation.md docs/specs/README.md docs/specs/platform-operator-authorization/spec.md docs/specs/platform-operator-authorization/openapi.yaml docs/specs/platform-operator-openapi.yaml redocly.yaml backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorAuthorizationOpenApiContractTest.java backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java
git commit -m "docs(global): 운영자 권한 공통 계약 확정"
```

### Task 2: Add the fixed catalog and V40 authority ledger

**Files:**
- Create: `backend/src/main/resources/db/migration/V40__create_platform_operator_authorization.sql`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorRole.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorPermission.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorRoleGrant.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorPermissionGrant.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/PlatformOperatorRoleGrantRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/PlatformOperatorPermissionGrantRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorRoleTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/repository/PlatformOperatorAuthorizationMigrationIT.java`

**Interfaces:**
- Produces: `PlatformOperatorRole.permissions(): Set<PlatformOperatorPermission>` and normalized grant tables keyed by `(account_id, role)` and `(account_id, permission)`.

- [ ] **Step 1: Write RED catalog tests**

```java
@Test
void superAdminDoesNotImplicitlyReceiveBroadReadPermissions() {
    assertThat(PlatformOperatorRole.SUPER_ADMIN.permissions())
            .contains(OPERATOR_CREATE, OPERATOR_AUTHORITY_MANAGE, OPERATOR_SUSPEND)
            .doesNotContain(MEMBER_READ_MINIMAL, ONBOARDING_EVIDENCE_READ, AUDIT_READ);
}
```

- [ ] **Step 2: Run and verify RED**

Run: `cd backend; .\gradlew.bat test --tests "*PlatformOperatorRoleTest"`

Expected: compilation failure because the catalog types do not exist.

- [ ] **Step 3: Implement the exact enums and role mappings**

Use immutable `Set.of(...)` mappings. Include only the roles and permissions approved in the design.

- [ ] **Step 4: Verify catalog GREEN**

Run: `cd backend; .\gradlew.bat test --tests "*PlatformOperatorRoleTest"`

Expected: PASS.

- [ ] **Step 5: Write RED MySQL migration tests**

Verify Flyway creates all five V40 tables, duplicate grants fail, invalid enum strings fail, approval digest is unique, and the singleton guard row has ID `1`.

- [ ] **Step 6: Implement V40 and JPA mappings**

Create role grants, permission grants, `admin_case_assignments`, `platform_operator_reauthentication_approvals`, and `platform_operator_authority_guard`. Add FK `ON DELETE RESTRICT`, positive version checks, and assignment/approval lookup indexes.

- [ ] **Step 7: Verify MySQL GREEN**

Run: `cd backend; .\gradlew.bat integrationTest --tests "*PlatformOperatorAuthorizationMigrationIT"`

Expected: PASS against Testcontainers MySQL.

- [ ] **Step 8: Commit the ledger**

```powershell
git add -- backend/src/main/resources/db/migration/V40__create_platform_operator_authorization.sql backend/src/main/java/com/miriyum/domain/platformoperator/enums backend/src/main/java/com/miriyum/domain/platformoperator/entity backend/src/main/java/com/miriyum/domain/platformoperator/repository backend/src/test/java/com/miriyum/domain/platformoperator/enums backend/src/test/java/com/miriyum/domain/platformoperator/repository/PlatformOperatorAuthorizationMigrationIT.java
git commit -m "feat(global): 운영자 역할 권한 원장 추가"
```

### Task 3: Implement current authority reads and versioned mutations

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/OperatorAuthority.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/OperatorAuthorityReader.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/OperatorAuthorityService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorAccount.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/PlatformOperatorAccountRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/OperatorAuthorityServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/OperatorAuthorityConcurrencyIT.java`

**Interfaces:**
- Produces: `OperatorAuthorityReader.currentAuthority(long operatorId)` and transaction-only grant/revoke methods that advance both authority and session versions.

- [ ] **Step 1: Write RED authority aggregation tests**

```java
OperatorAuthority authority = reader.currentAuthority(7L);
assertThat(authority.permissions()).containsAll(rolePermissions).contains(DIRECT_PERMISSION);
assertThat(authority.authorityVersion()).isEqualTo(3L);
```

- [ ] **Step 2: Verify RED**

Run: `cd backend; .\gradlew.bat test --tests "*OperatorAuthorityServiceTest"`

Expected: compilation failure for missing public contract.

- [ ] **Step 3: Implement aggregation and locked mutations**

Define:

```java
public interface OperatorAuthorityReader {
    OperatorAuthority currentAuthority(long operatorId);
}

public record OperatorAuthority(
        long operatorId,
        long authorityVersion,
        Set<PlatformOperatorRole> roles,
        Set<PlatformOperatorPermission> permissions) {}
```

Mutations must call `findByIdForUpdate`, update grants, then `advanceAuthorityVersion()` in one transaction.

- [ ] **Step 4: Verify unit GREEN**

Run: `cd backend; .\gradlew.bat test --tests "*OperatorAuthorityServiceTest"`

Expected: PASS.

- [ ] **Step 5: Add and run MySQL revocation race test**

Use two executors and a barrier: one revokes a permission and advances versions; the other reads with the old expected version. Assert the post-revocation request is rejected on every repository/service instance.

Run: `cd backend; .\gradlew.bat integrationTest --tests "*OperatorAuthorityConcurrencyIT"`

Expected: PASS, no stale grant accepted after revoke commit.

- [ ] **Step 6: Commit authority services**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/platformoperator backend/src/test/java/com/miriyum/domain/platformoperator/service
git commit -m "feat(global): 운영자 권한 버전 검증 추가"
```

### Task 4: Implement central case assignment verification

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/AdminCaseType.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/AdminCaseAssignmentStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/AdminCaseAssignment.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/AdminCaseAssignmentRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/AdminCaseAssignmentVerifier.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/AdminCaseAssignmentService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/AdminCaseAssignmentRepository.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/AdminCaseAssignmentServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/AdminCaseAssignmentConcurrencyIT.java`

**Interfaces:**
- Produces: `AdminCaseAssignmentVerifier.verify(AdminCaseAssignmentRequest request)` for scalar case type/ID/version/operator/instant input.

- [ ] **Step 1: Write RED assignment tests**

Test exact operator, `ASSIGNED` status, exact case version, and `expiresAt > now`; each mismatch must throw the shared authorization denial.

- [ ] **Step 2: Verify RED**

Run: `cd backend; .\gradlew.bat test --tests "*AdminCaseAssignmentServiceTest"`

Expected: missing verifier and case model compilation failure.

- [ ] **Step 3: Implement minimal assignment entity/repository/service**

Do not import any #277–#282 entity or repository. Use string public IDs and `AdminCaseType` only.

- [ ] **Step 4: Verify unit GREEN**

Run: `cd backend; .\gradlew.bat test --tests "*AdminCaseAssignmentServiceTest"`

Expected: PASS.

- [ ] **Step 5: Verify MySQL reassignment/version races**

Run: `cd backend; .\gradlew.bat integrationTest --tests "*AdminCaseAssignmentConcurrencyIT"`

Expected: an old assignment/version is rejected after reassignment or closure commit.

- [ ] **Step 6: Commit assignment verification**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/platformoperator backend/src/test/java/com/miriyum/domain/platformoperator/service
git commit -m "feat(global): 운영자 사건 배정 검증 추가"
```

### Task 5: Issue current-password-bound one-time approvals over HTTP

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/AdminCommandPurpose.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/AdminTargetType.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorReauthenticationApproval.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/PlatformOperatorReauthenticationApprovalRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/ReauthenticationApprovalRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/ReauthenticationApprovalResult.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/ReauthenticationApprovalResponse.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/ReauthenticationService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/controller/authorization/PlatformOperatorAuthorizationController.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/exception/AdminAuthorizationErrorCode.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/config/PlatformOperatorAuthProperties.java`
- Modify: `backend/src/main/resources/application.yml`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/ReauthenticationServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/controller/authorization/PlatformOperatorAuthorizationHttpIT.java`

**Interfaces:**
- Produces: `ReauthenticationService.issue(PlatformOperatorPrincipal, ReauthenticationApprovalRequest)` and the OpenAPI endpoint.

- [ ] **Step 1: Write RED service tests**

Assert current password verification, exact five-minute expiry, authority/session binding, CSPRNG plaintext returned once, digest-only persistence, and no secret in `toString()`/audit fields.

- [ ] **Step 2: Verify RED**

Run: `cd backend; .\gradlew.bat test --tests "*ReauthenticationServiceTest"`

Expected: missing implementation compilation failure.

- [ ] **Step 3: Implement approval issuance**

Use `SecureRandom`, URL-safe Base64 without padding, SHA-256 digest, and an HMAC-SHA-256 session fingerprint derived from an injected secret. Validate a positive configured TTL equal to five minutes when the feature is enabled.

- [ ] **Step 4: Verify service GREEN**

Run: `cd backend; .\gradlew.bat test --tests "*ReauthenticationServiceTest"`

Expected: PASS.

- [ ] **Step 5: Write and run HTTP tests**

Assert valid platform-operator Bearer succeeds, consumer/store-operator tokens fail, limited initial-password sessions fail, wrong password returns `401 ADMIN_002`, unknown fields return 400, and feature OFF returns MVC 404.

Run: `cd backend; .\gradlew.bat test --tests "*PlatformOperatorAuthorizationHttpIT"`

Expected: PASS.

- [ ] **Step 6: Commit approval issuance**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/platformoperator backend/src/main/resources/application.yml backend/src/test/java/com/miriyum/domain/platformoperator
git commit -m "feat(global): 운영자 현재 비밀번호 재인증 추가"
```

### Task 6: Implement atomic high-risk guard and audit context

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/HighRiskCommandRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/AdminAuditContext.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/HighRiskCommandGuard.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/HighRiskCommandGuardTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/HighRiskCommandGuardConcurrencyIT.java`

**Interfaces:**
- Consumes: `OperatorAuthorityReader`, `AdminCaseAssignmentVerifier`, approval repository, #275 principal/account lock.
- Produces: `AdminAuditContext authorize(HighRiskCommandRequest request)`; callers must already have an active transaction.

- [ ] **Step 1: Write RED guard tests**

```java
assertDenied(requestWithoutPermission());
assertDenied(requestWithoutAssignment());
assertDenied(requestWithoutApproval());
assertDenied(reusedForOtherPurpose());
assertDenied(reusedForOtherTarget());
assertDenied(reusedFromOtherSession());
```

Also assert successful context contains role/permission/version/case/purpose/target/correlation data but no plaintext approval or raw session ID.

- [ ] **Step 2: Verify RED**

Run: `cd backend; .\gradlew.bat test --tests "*HighRiskCommandGuardTest"`

Expected: missing guard compilation failure.

- [ ] **Step 3: Implement the ordered guard**

Require an active transaction, lock account, compare principal/current authority versions, require permission, verify assignment, then consume approval with one conditional update over digest and every binding field. Map all missing/mismatched/expired/consumed outcomes to `403 ADMIN_001`.

- [ ] **Step 4: Verify unit GREEN**

Run: `cd backend; .\gradlew.bat test --tests "*HighRiskCommandGuardTest"`

Expected: PASS.

- [ ] **Step 5: Run one-time reuse and revoke races on MySQL**

Run: `cd backend; .\gradlew.bat integrationTest --tests "*HighRiskCommandGuardConcurrencyIT"`

Expected: exactly one concurrent consumer succeeds; a revoke committed first makes every old-version request fail.

- [ ] **Step 6: Commit the guard**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/platformoperator backend/src/test/java/com/miriyum/domain/platformoperator/service
git commit -m "feat(global): 고위험 운영 명령 가드 추가"
```

### Task 7: Protect the final active super administrator

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorAuthorityGuard.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/PlatformOperatorAuthorityGuardRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/LastSuperAdminPolicy.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/LastSuperAdminPolicyTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/service/LastSuperAdminConcurrencyIT.java`

**Interfaces:**
- Produces: `LastSuperAdminPolicy.assertRemovable(long targetOperatorId)` for #282 transaction use.

- [ ] **Step 1: Write RED policy tests**

Assert a non-super-admin is removable, one of two active super-admins is removable, and the sole active super-admin is rejected with `409 ADMIN_003`.

- [ ] **Step 2: Verify RED**

Run: `cd backend; .\gradlew.bat test --tests "*LastSuperAdminPolicyTest"`

Expected: missing policy compilation failure.

- [ ] **Step 3: Implement singleton-lock counting**

Lock `platform_operator_authority_guard(id=1)` before counting active accounts with a `SUPER_ADMIN` grant. Require an active caller transaction.

- [ ] **Step 4: Verify unit and MySQL GREEN**

Run: `cd backend; .\gradlew.bat test --tests "*LastSuperAdminPolicyTest"`

Run: `cd backend; .\gradlew.bat integrationTest --tests "*LastSuperAdminConcurrencyIT"`

Expected: concurrent removals never leave zero active super-admins.

- [ ] **Step 5: Commit final-super-admin protection**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/platformoperator backend/src/test/java/com/miriyum/domain/platformoperator/service
git commit -m "feat(global): 마지막 슈퍼관리자 보호 추가"
```

### Task 8: Close architecture, HTTP, OpenAPI, and regression gates

**Files:**
- Modify: `backend/ai/implementation-guardrails.md`
- Modify: `backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorAuthorizationOpenApiContractTest.java`
- Modify: files already listed in Tasks 1–7 only when verification exposes an in-scope defect.

**Interfaces:**
- Produces: verified #277–#282 handoff and final evidence.

- [ ] **Step 1: Run focused authorization suite**

Run: `cd backend; .\gradlew.bat test --tests "*platformoperator*"`

Expected: all unit/slice authorization tests PASS.

- [ ] **Step 2: Run actual MySQL authorization suite**

Run: `cd backend; .\gradlew.bat integrationTest --tests "*PlatformOperatorAuthorizationMigrationIT" --tests "*OperatorAuthorityConcurrencyIT" --tests "*AdminCaseAssignmentConcurrencyIT" --tests "*HighRiskCommandGuardConcurrencyIT" --tests "*LastSuperAdminConcurrencyIT"`

Expected: all MySQL constraint, revocation, assignment, one-time reuse, and final-super-admin races PASS.

- [ ] **Step 3: Run full regressions**

Run: `cd backend; .\gradlew.bat test`

Run: `cd backend; .\gradlew.bat integrationTest`

Run: `cd backend; .\gradlew.bat build`

Expected: each exits 0 with no failed tests.

- [ ] **Step 4: Run OpenAPI and diff gates**

Run: `npx @redocly/cli lint platform-operator --config redocly.yaml`

Run: `git diff --check`

Expected: OpenAPI lint exits 0 and `git diff --check` prints no errors.

- [ ] **Step 5: Inspect scope and secrets**

```powershell
git diff --name-only origin/dev...HEAD
rg -n "currentPassword|X-Admin-Reauthentication|sessionId|approval" backend/src/main/java/com/miriyum/domain/platformoperator
```

Expected: only allowlisted files changed; persistence/log/audit code contains no plaintext secret fields.

- [ ] **Step 6: Commit verification alignment**

```powershell
git add -- backend/ai/implementation-guardrails.md backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorAuthorizationOpenApiContractTest.java
git commit -m "test(global): 운영자 권한 공통 계약 회귀 검증"
```

- [ ] **Step 7: Publish**

Push `feature/276-admin-rbac`, create a Draft PR targeting `dev`, and include the exact commands/results, rollback notes, #277–#282 interfaces, remaining WebAuthn/audit-retention/domain-command constraints, and `Closes #276`.
