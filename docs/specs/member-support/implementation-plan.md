# Member Support Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the #278 member lookup, inaccessible-email recovery, sanctions, permanent-sanction approval, and appeal workflows on the merged #275/#276 platform-operator foundation.

**Architecture:** `platformoperator` owns case, verification, sanction, approval, and audit ledgers. `consumer` and `storeoperator` implement a shared `auth.membersupport.MemberAccountSupportPort` so platformoperator never imports foreign entities or repositories. All high-risk state changes join one MySQL transaction, call `HighRiskCommandGuard`, and CAS the target account `supportVersion` before appending audit data.

**Tech Stack:** Java 21, Spring Boot 4.1, Spring MVC/Security, Spring Data JPA, Flyway, MySQL 8.0.40 Testcontainers, JUnit 5, AssertJ, MockMvc, Redocly CLI 2.35.1.

## Global Constraints

- Work only in `feature/278-admin-member-support` and its isolated worktree; never modify the user's original tree or push directly to `dev`.
- The only member-support schema file is `backend/src/main/resources/db/migration/V43__create_member_support.sql`; latest `dev` owns V42 and existing migrations are never edited.
- Controllers and services exist only when both `miriyum.platform-operator.enabled=true` and `miriyum.member-support.enabled=true`; the external paths are real MVC 404 when disabled.
- The dev mock verifier is usable only when `miriyum.identity-verification.dev-stub-enabled=true`; otherwise verification fails closed.
- Member-support configuration uses a distinct `proof-digest-secret` plus versioned AES-256-GCM `pii-encryption-active-key-version`/`pii-encryption-active-key` and optional matching previous pair. Ciphertext is `version || nonce || ciphertext`, new writes use only active, and reads accept active/previous during rotation.
- Permission checks happen before account lookup. Missing IDs and IDs in the other account type both become `AUTH_016`; anonymous recovery/appeal submissions always return the same 202 body.
- Never return or log password, JWT, cookie, OTP, raw reauthentication approval, mock proof, email, phone, business number, or representative name. Store the pending new email only as AES-256-GCM ciphertext and its equality digest.
- Warning has no expiry, feature restriction lasts exactly 7 days, temporary suspension lasts exactly 30 days, and permanent suspension needs a different SUPER_ADMIN with `ACCOUNT_PERMANENT_SANCTION_APPROVE`.
- Recovery approval, sanction application, permanent approval, and appeal decision must CAS one target `support_version`. A concurrent loser rolls back case state, approval consumption, and audit.
- Every production behavior follows RED -> observed expected failure -> minimal GREEN -> refactor. Tests assert returned state and persisted side effects, not mock invocation counts.

---

### Task 1: Contract enums, account guard columns, and V43 schema

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/auth/exception/AuthErrorCode.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberAccountType.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberStatus.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberSanctionLevel.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/RestrictedFeature.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/AdminCommandPurpose.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorPermission.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorRole.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/exception/AdminAuthorizationErrorCode.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/entity/ConsumerAccount.java`
- Modify: `backend/src/main/java/com/miriyum/domain/storeoperator/entity/StoreOperatorAccount.java`
- Create: `backend/src/main/resources/db/migration/V43__create_member_support.sql`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportMigrationIT.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportCatalogTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/auth/membersupport/MemberAccountGuardTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorRoleTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorAuthorizationOpenApiContractTest.java`

**Interfaces:**
- Produces account fields `boolean passwordResetRequired` and `long supportVersion` plus methods `approveRecovery(String email)`, `replaceRecoveredPassword(String hash)`, `applySuspension()`, `clearSuspension()`, and `assertSupportVersion(long expected)`.
- Produces error codes `AUTH_016 MEMBER_SUPPORT_NOT_FOUND`, `AUTH_017 MEMBER_SUPPORT_STATE_CONFLICT`, and `AUTH_018 PERMANENT_SANCTION_APPROVAL_CONFLICT`.

- [x] **Step 1: Write the failing migration and catalog tests.** The migration test starts MySQL 8.0.40, runs Flyway, verifies V43, the two new account guard columns, five append/state ledger tables, active-case constraints, recovery-audit retention timestamp, and that the V43 replacement permission check accepts `ACCOUNT_PERMANENT_SANCTION_APPROVE`.

```java
assertThat(appliedVersions(dataSource)).contains("42");
assertThat(columns(dataSource, "consumer_accounts"))
        .contains("password_reset_required", "support_version");
assertThat(PlatformOperatorRole.SUPER_ADMIN.defaultPermissions())
        .contains(PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE);
```

- [x] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*PlatformOperatorRoleTest" --tests "*PlatformOperatorAuthorizationOpenApiContractTest"`

Run: `backend\gradlew.bat integrationTest --tests "*MemberSupportMigrationIT"`

Expected: compile/assertion failures for missing enum values, error codes, V43, and account columns.

- [x] **Step 3: Implement the minimal catalogs, entity transitions, and migration.** V43 creates `member_identity_verifications`, `member_support_cases`, `member_sanctions`, `member_sanction_approvals`, `member_support_audits`, and the expiry/active-case guard indexes. It drops and recreates only the V40 permission check constraint to include the new permission.

```java
public void assertSupportVersion(long expected) {
    if (supportVersion != expected) throw new ServiceException(AuthErrorCode.MEMBER_SUPPORT_STATE_CONFLICT);
}

public void approveRecovery(String newEmail) {
    email = Objects.requireNonNull(newEmail);
    passwordResetRequired = true;
    supportVersion++;
}
```

- [x] **Step 4: Run GREEN and commit.**

Run the two RED commands again; expected PASS.

Commit: `feat: add member support schema and catalogs`

### Task 2: Account-type ports and minimal account projection

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberAccountSnapshot.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberSearchCriteria.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberAccountPage.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberAccountSupportPort.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberAccountSupportRegistry.java`
- Create: `backend/src/main/java/com/miriyum/domain/consumer/membersupport/ConsumerMemberSupportRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/consumer/membersupport/ConsumerMemberSupportAdapter.java`
- Create: `backend/src/main/java/com/miriyum/domain/storeoperator/membersupport/StoreOperatorMemberSupportRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/storeoperator/membersupport/StoreOperatorMemberSupportAdapter.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/service/ConsumerAuthService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/service/ConsumerKakaoAuthService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/storeoperator/service/StoreOperatorAuthService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/storeoperator/service/StoreOperatorKakaoAuthService.java`
- Test: `backend/src/test/java/com/miriyum/domain/consumer/membersupport/ConsumerMemberSupportAdapterTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/storeoperator/membersupport/StoreOperatorMemberSupportAdapterTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/auth/membersupport/MemberAccountSupportRegistryTest.java`

**Interfaces:**
- Produces:

```java
public interface MemberAccountSupportPort {
    MemberAccountType accountType();
    Optional<MemberAccountSnapshot> findMinimal(long accountId);
    Optional<MemberAccountSnapshot> findRecoveryTarget(String oldEmail, String registeredPhone);
    MemberAccountPage search(MemberSearchCriteria criteria, int offset, int limit);
    long approveRecovery(long accountId, long expectedVersion, String newEmail);
    long applySuspension(long accountId, long expectedVersion);
    long clearSuspension(long accountId, long expectedVersion);
    void replaceRecoveredPassword(long accountId, String newPassword);
}
```

- `MemberAccountSupportRegistry.require(MemberAccountType)` returns the correct port without probing the other account table.

- [x] **Step 1: Write adapter tests.** Name the mutations they catch: wrong account type routing, PII in minimal projection, failure to block login during password reset, failure to revoke all refresh state, and support-version mismatch.

```java
assertThat(adapter.findMinimal(id).orElseThrow())
        .extracting(MemberAccountSnapshot::accountType, MemberAccountSnapshot::accountId)
        .containsExactly(MemberAccountType.CONSUMER, id);
assertThatThrownBy(() -> authService.login(login))
        .isInstanceOfSatisfying(ServiceException.class,
                ex -> assertThat(ex.getErrorCode()).isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
```

- [x] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*MemberSupportAdapterTest" --tests "*MemberAccountSupportRegistryTest"`

Expected: missing port/adapter types and login accepting `passwordResetRequired` accounts.

- [x] **Step 3: Implement ports with own-domain repositories.** Use Querydsl/JPA projections inside each account domain, lock the account row for mutations, call entity CAS methods, encode the recovered password with the existing `PasswordPolicy`/`PasswordEncoder`, and revoke the namespace with `RefreshTokenManager.revokeAll`.

- [x] **Step 4: Run GREEN and commit.**

Commit: `feat: expose member account support ports`

### Task 3: Opaque mock verification and encrypted recovery payload

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/MemberIdentityVerification.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberIdentityVerificationRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportCrypto.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MockMemberIdentityVerificationService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportProperties.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/membersupport/PublicMemberSupportRequests.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/resources/application-test.yml`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MockMemberIdentityVerificationServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/global/config/MemberSupportConfigurationTest.java`

**Interfaces:**
- `issueRecovery(MemberAccountType, RecoveryVerificationCommand)` always returns a random fixed-length `OpaqueProof`; only a valid account/proof request persists its digest and encrypted email.
- `consumeForSubmission(String rawProof, MemberAccountType, Purpose)` atomically marks the digest consumed and returns account ID plus decrypted new email only to the transaction caller.
- AES-GCM ciphertext uses a random 96-bit nonce; the configured base64 key decodes to exactly 32 bytes; HMAC-SHA-256 produces equality digests with a separate configured key.

- [x] **Step 1: Write failing tests for one-time binding and leakage.** Assert same plaintext encrypts differently, wrong purpose/type/account fails, concurrent consume succeeds once, `toString()` redacts all request secrets, and disabled mock returns no proof.

```java
OpaqueProof proof = service.issueRecovery(MemberAccountType.CONSUMER, valid);
assertThat(service.consumeForSubmission(proof.value(), CONSUMER, MEMBER_RECOVERY)).isPresent();
assertThat(service.consumeForSubmission(proof.value(), CONSUMER, MEMBER_RECOVERY)).isEmpty();
assertThat(proof.toString()).doesNotContain(proof.value());
```

- [x] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*MockMemberIdentityVerificationServiceTest" --tests "*MemberSupportConfigurationTest"`

- [x] **Step 3: Implement minimal crypto, property validation, and digest consumption.** Do not log submitted fields. Store old-email/phone/business checks only as booleans; persist only target IDs, purpose, ciphertext/digest, expiry, and consume timestamps.

- [x] **Step 4: Run GREEN and commit.**

Commit: `feat: add opaque member verification proofs`

### Task 4: Recovery and appeal submission with uniform public HTTP responses

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/MemberSupportCase.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberSupportCaseRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportSubmissionService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportCookieFactory.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/controller/membersupport/MemberRecoveryController.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/controller/membersupport/MemberAppealController.java`
- Modify: `backend/src/main/java/com/miriyum/global/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportSubmissionServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/PublicMemberSupportHttpIT.java`
- Test: `backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java`

**Interfaces:**
- Recovery cases transition `SUBMITTED -> ASSIGNED -> APPROVED|REJECTED`; appeal cases transition `SUBMITTED -> ASSIGNED -> UPHELD|REDUCED|CANCELLED`.
- All verification, recovery submission, and appeal submission endpoints return `202` with `ApiResponse.success(message, null)`, whether a case was created or not.

- [ ] **Step 1: Write failing service and MockMvc tests.** Use literal equal responses and same-length `Set-Cookie` headers for valid/missing/wrong-type/wrong-proof requests and assert only the valid request persists a digest or inserts a case. Assert proof cookies are `HttpOnly; Secure; SameSite=Strict` with namespace-specific paths.

- [ ] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*MemberSupportSubmissionServiceTest"`

Run: `backend\gradlew.bat integrationTest --tests "*PublicMemberSupportHttpIT"`

- [ ] **Step 3: Implement controllers and service.** Add an order-1 public member-support chain before the existing account chains. Conditional controllers ensure disabled flags fall through to MVC 404 rather than returning a security 401.

- [ ] **Step 4: Run GREEN and commit.**

Commit: `feat: accept private member support submissions`

### Task 5: Permission-first minimal member and case queries, assignment

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/membersupport/MemberSupportResponses.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/membersupport/PlatformMemberSupportRequests.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportAuthorizationService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportQueryService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportAssignmentService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/controller/membersupport/PlatformOperatorMemberSupportController.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportQueryServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportAssignmentServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/PlatformOperatorMemberSupportHttpIT.java`

**Interfaces:**
- `requirePermission(principal, permission)` validates the principal authority version before any `MemberAccountSupportPort` call.
- Optional cross-type listing fetches `offset + size` from each port, merges by the requested `joinedAt,accountId` order, then slices; detail calls only the requested type port.
- Assignment moves a case to `ASSIGNED`, increments case version, then calls `AdminCaseAssignmentManager.assign` with that new version in the same transaction.
- A direct sanction request creates an enforcement case, assigns its current version to the caller, then invokes the guard in the same transaction. A permanent proposal closes that assignment and increments the case version; the different SUPER_ADMIN must assign the new version before additional approval.

- [ ] **Step 1: Write permission/existence tests.** A port fake that throws if touched proves an unauthorized query returns `ADMIN_001` before lookup. Missing and wrong-type detail requests both assert `AUTH_016` and the wrong port remains untouched.

- [ ] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*MemberSupportQueryServiceTest" --tests "*MemberSupportAssignmentServiceTest"`

- [ ] **Step 3: Implement query, page merge, assignment, and minimal response mapping.** Responses contain only type, public ID, computed status, joinedAt, supportVersion, and active sanction summaries.

- [ ] **Step 4: Run HTTP integration and commit.**

Run: `backend\gradlew.bat integrationTest --tests "*PlatformOperatorMemberSupportHttpIT"`

Commit: `feat: add permission-first member support queries`

### Task 6: Recovery decision and recovered-password completion

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/MemberSupportAudit.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberSupportAuditRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportAuditWriter.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberRecoveryService.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberRecoveryServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberRecoveryHttpIT.java`

**Interfaces:**
- `decideRecovery(principal, caseId, expectedVersion, approval, idempotencyKey, decision)` calls the high-risk guard with `MEMBER_RECOVERY`, changes the target account through its port, closes the central assignment, and appends minimal audit.
- `completePasswordReset(rawProof, accountType, newPassword)` accepts only an approved, unused recovery case tied to that proof, replaces the password, revokes sessions, and never returns a token.

- [ ] **Step 1: Write failing atomicity tests.** Assert approval changes email, increments support version, revokes refresh state, blocks password/Kakao login, persists one redacted audit, and rollback restores every row when account CAS fails.

- [ ] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*MemberRecoveryServiceTest"`

- [ ] **Step 3: Implement the transaction and HTTP decision/reset paths.** Build `HighRiskCommandRequest` with `MEMBER_SUPPORT`, current assigned case version, target account type/ID, and request correlation ID. Persist only decision code, fingerprints, versions, public IDs, and timestamps.

- [ ] **Step 4: Run GREEN and commit.**

Commit: `feat: approve and complete account recovery`

### Task 7: Sanctions, feature restriction enforcement, expiry, and permanent approval

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/MemberSanction.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/MemberSanctionApproval.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberSanctionRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberSanctionApprovalRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSanctionService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSanctionExpiryService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/config/membersupport/MemberRestrictionFilter.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/config/membersupport/MemberRestrictionPolicy.java`
- Modify: `backend/src/main/java/com/miriyum/global/security/SecurityConfig.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSanctionServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSanctionExpiryIT.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/config/membersupport/MemberRestrictionFilterTest.java`

**Interfaces:**
- `applySanction(...)` immediately applies warning/restriction/temp suspension; permanent creates `PENDING_ADDITIONAL_APPROVAL`.
- `approvePermanent(...)` requires a different operator with SUPER_ADMIN role and `ACCOUNT_PERMANENT_SANCTION_APPROVE`, consumes a separate `PERMANENT_ACCOUNT_SANCTION_APPROVAL` proof, and records one approval row.
- The restriction policy maps HTTP writes to `RESERVATION`, `WAITING`, `PICKUP`, `STORE_OPERATION`, or `MENU_OPERATION`; reads and support/recovery/appeal paths remain allowed.

- [ ] **Step 1: Write literal duration, self-approval, feature-route, and expiry tests.** Mutating 7 to 8 days, 30 to 29 days, allowing the proposer, or blocking a GET must fail a test.

- [ ] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*MemberSanctionServiceTest" --tests "*MemberRestrictionFilterTest"`

- [ ] **Step 3: Implement sanctions and filter.** Temporary/permanent suspension also calls the account port to revoke sessions and block login. Expiry uses row locks and idempotent status transitions; it clears account suspension only when no other active suspension remains.

- [ ] **Step 4: Run GREEN, MySQL expiry test, and commit.**

Run: `backend\gradlew.bat integrationTest --tests "*MemberSanctionExpiryIT"`

Commit: `feat: enforce member sanctions`

### Task 8: Appeal assignment and decisions

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberAppealService.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberAppealServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberAppealHttpIT.java`

**Interfaces:**
- `decideAppeal(...)` accepts `UPHOLD`, `REDUCE`, or `CANCEL`, calls the guard with `ACCOUNT_APPEAL_DECISION`, and appends a new sanction revision for reduce/cancel rather than mutating history.
- Permanent-sanction appeal outcomes require a current SUPER_ADMIN; the existing sanction remains active until commit.

- [ ] **Step 1: Write failing tests for one active appeal, assignment, upheld/reduced/cancelled outcomes, and permanent SUPER_ADMIN enforcement.**

```java
assertThat(service.decideAppeal(command(REDUCE))).satisfies(result -> {
    assertThat(result.caseStatus()).isEqualTo(REDUCED);
    assertThat(result.sanctionLevel()).isEqualTo(FEATURE_RESTRICTION);
    assertThat(result.endsAt()).isEqualTo(now.plus(Duration.ofDays(7)));
});
```

- [ ] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*MemberAppealServiceTest"`

- [ ] **Step 3: Implement immutable revisions and high-risk audit.** Reject equal/higher “reduction”, missing feature sets, stale versions, and terminal cases with `AUTH_017`.

- [ ] **Step 4: Run GREEN and commit.**

Commit: `feat: decide member sanction appeals`

### Task 9: Real-MySQL races, HTTP/OpenAPI drift, and leakage regression

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportConcurrencyIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportAuditPrivacyTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/ControllerOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/OpenApiRouteInventory.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/OpenApiRouteInventoryTest.java`
- Modify: `docs/specs/member-support/openapi.yaml`
- Modify: `docs/specs/member-support/spec.md`

**Interfaces:**
- Produces executable proof that recovery-versus-sanction races have exactly one success, one `AUTH_017`, one audit row, one consumed admin approval, and one support-version increment.

- [ ] **Step 1: Write the failing race and contract tests.** Use two real transactions, `CountDownLatch`, and MySQL 8.0.40. Do not use sleeps. Capture both results and inspect committed ledgers afterward.

- [ ] **Step 2: Run RED.**

Run: `backend\gradlew.bat integrationTest --tests "*MemberSupportConcurrencyIT"`

Run: `backend\gradlew.bat test --tests "*OpenApi*" --tests "*HttpApiNamespaceContractTest" --tests "*DomainPackageArchitectureTest"`

- [ ] **Step 3: Make only the minimal transaction/order and route-inventory changes needed for GREEN.** Ensure account row/CAS happens before audit append and all guard consumption remains in the same transaction.

- [ ] **Step 4: Run GREEN and commit.**

Commit: `test: cover member support races and contracts`

### Task 10: Full verification, implementation commit, push, and Draft PR

**Files:**
- Modify only files already listed in `docs/specs/member-support/spec.md` when verification exposes a real defect; every fix starts with a failing regression test.

- [ ] **Step 1: Run targeted unit and contract verification.**

Run: `backend\gradlew.bat test`

Expected: all non-integration tests PASS with no failed test report.

- [ ] **Step 2: Run all real integration shards.**

Run: `backend\gradlew.bat integrationTestShardA`

Run: `backend\gradlew.bat integrationTestShardB`

Run: `backend\gradlew.bat integrationTestShardC`

Run: `backend\gradlew.bat integrationTestShardD`

Expected: all MySQL/Valkey/HTTP integration tests PASS; Docker unavailability is BLOCKED, never reported as PASS.

- [ ] **Step 3: Run OpenAPI strict lint.**

Run: `npx --yes @redocly/cli@2.35.1 lint member-support platform-operator platform-operator-authorization docs/specs/consumer-openapi.yaml`

Run: `npx --yes @redocly/cli@2.35.1 lint docs/specs/store-operator-openapi.yaml`

Expected: changed member-support contracts PASS. If the store aggregate still reports only the four pre-existing `store-search` conditional-schema errors, record them separately and prove the member-support source/refs pass; do not edit unrelated store-search files.

- [ ] **Step 4: Verify allowlist and whitespace.**

Run: `git diff origin/dev --name-only`

Run: `git diff origin/dev --check`

Expected: every path is in the active spec allowlist and no whitespace errors.

- [ ] **Step 5: Use `superpowers:requesting-code-review`, fix every confirmed issue test-first, then use `superpowers:verification-before-completion` and `superpowers:finishing-a-development-branch`.**

- [ ] **Step 6: Commit remaining verified changes, push only `feature/278-admin-member-support`, and create a Draft PR targeting `dev`.** Include test evidence, known pre-existing store aggregate lint debt, migration V43, security invariants, and no direct dev push.

### Task 11: Separate approval-gated password reset credential

**Files:**
- Modify: `backend/src/main/resources/db/migration/V43__create_member_support.sql`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/MemberIdentityVerification.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/MemberSupportCase.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberIdentityVerificationRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberSupportCaseRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberPasswordResetCredentialService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/controller/membersupport/MemberRecoveryController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportCookieFactory.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportProperties.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberRecoveryPasswordResetTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/PublicMemberSupportHttpIT.java`

**Interfaces:**
- Recovery submission renews only an opaque case-tracking cookie for the configured recovery-completion window.
- After approval, `exchange(MemberAccountType, trackingProof)` rotates a distinct `PASSWORD_RESET` verification and returns a short-lived opaque reset proof.
- Password reset locks the reset verification and approved case, validates purpose/type/expiry/current pointer, consumes it once, and marks the case reset-complete in the same transaction.

- [ ] **Step 1: Add failing tests for approval after the original 15-minute verification TTL, expired reset proof, double use, and an older rotated proof.**

```java
assertThat(credentials.exchange(CONSUMER, trackingProof)).isPresent();
credentials.reset(CONSUMER, resetProof, "Changed2@");
assertThatThrownBy(() -> credentials.reset(CONSUMER, resetProof, "Changed3#"))
        .isInstanceOf(ServiceException.class);
```

- [ ] **Step 2: Run RED.**

Run: `backend\gradlew.bat test --tests "*MemberRecoveryPasswordResetTest"`

- [ ] **Step 3: Implement the separate credential, V43 columns/checks, cookie paths and approval-gated exchange HTTP path.** Never persist or log the raw tracking/reset proof.

- [ ] **Step 4: Run GREEN and the focused public HTTP/MySQL tests.**

### Task 12: Store recovery ownership evidence and appeal contact evidence

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberAccountSupportPort.java`
- Create: `backend/src/main/java/com/miriyum/domain/auth/membersupport/StoreRecoveryEvidencePort.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/membersupport/StoreRecoveryEvidenceAdapter.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/membersupport/StoreRecoveryEvidenceRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/membersupport/ConsumerMemberSupportAdapter.java`
- Modify: `backend/src/main/java/com/miriyum/domain/storeoperator/membersupport/StoreOperatorMemberSupportAdapter.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/membersupport/PublicMemberSupportRequests.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MockMemberIdentityVerificationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportSubmissionService.java`
- Test: `backend/src/test/java/com/miriyum/domain/store/membersupport/StoreRecoveryEvidenceAdapterTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MockMemberIdentityVerificationServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportSubmissionServiceTest.java`

**Interfaces:**
- Store recovery succeeds only when account email, phone, representative display name, and one owned store business number all match.
- Appeal verification is purpose/account/sanction-bound, checks the selected registered contact channel, is consumed once, and persists no raw contact or statement.

- [ ] **Step 1: Add failing mismatch tests for business number, representative name, wrong contact, another account contact and replay.**
- [ ] **Step 2: Run RED with the three focused test classes.**
- [ ] **Step 3: Implement the dedicated store-owned adapter and appeal verification lifecycle.**
- [ ] **Step 4: Run GREEN and privacy assertions.**

### Task 13: Apply account CAS to every enforcement and appeal decision

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/auth/membersupport/MemberAccountSupportPort.java`
- Modify: `backend/src/main/java/com/miriyum/domain/consumer/entity/ConsumerAccount.java`
- Modify: `backend/src/main/java/com/miriyum/domain/storeoperator/entity/StoreOperatorAccount.java`
- Modify: both account member-support adapters
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSanctionService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberAppealService.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSanctionServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberAppealServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportConcurrencyIT.java`

**Interfaces:**
- `advanceSupportVersion(accountId, expectedVersion)` locks the owned account, checks the expected version and increments exactly once without changing suspension/password state.
- WARNING, FEATURE_RESTRICTION, UPHOLD, and any reduction/cancellation that does not call suspension transition use this CAS operation.

- [ ] **Step 1: Add failing unit tests and a MySQL recovery-versus-feature-restriction race.** Assert one success, one `AUTH_017`, and rollback of losing case/reauthentication/audit state.
- [ ] **Step 2: Run RED.**
- [ ] **Step 3: Add the owned account CAS operation and call it exactly once per final command.**
- [ ] **Step 4: Run GREEN for unit and real MySQL concurrency tests.**

### Task 14: Compute five member states from active sanction projection

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/entity/membersupport/MemberSanction.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberSanctionRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/ActiveMemberSanctionReader.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/membersupport/MemberSupportResponses.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportQueryService.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportQueryServiceTest.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/PlatformOperatorMemberSupportHttpIT.java`

**Interfaces:**
- The reader returns only APPLIED sanctions whose `appliedAt <= now` and whose optional `endsAt` is still in the future, grouped by account type and ID.
- Status precedence is PERMANENTLY_SUSPENDED, TEMPORARILY_SUSPENDED, PASSWORD_RESET_REQUIRED, FEATURE_RESTRICTED, ACTIVE.
- Filtering, total count, global joined-at/public-ID ordering and pagination run on the computed projection; every member response contains `activeSanctions` with level, feature set and nullable end time.

- [ ] **Step 1: Add failing projection tests for feature restriction, permanent suspension, summaries, total and second-page ordering.**
- [ ] **Step 2: Run RED.**
- [ ] **Step 3: Implement active sanction reading and computed projection.**
- [ ] **Step 4: Run GREEN including real MySQL/HTTP contract tests.**

### Task 15: Review remediation contract and publication

**Files:**
- Modify: `docs/specs/member-support/spec.md`
- Modify: `docs/specs/member-support/openapi.yaml`
- Modify audience OpenAPI refs only when route inventory requires them.

- [ ] **Step 1: Add contract tests for the reset-credential exchange path and required `activeSanctions`.**
- [ ] **Step 2: Update the active spec/OpenAPI and V43 only; do not add a later member-support migration.**
- [ ] **Step 3: Run changed OpenAPI lint, architecture tests, full unit suite and integration shards A-D.**
- [ ] **Step 4: Verify the exact allowlist and `git diff --check`, commit, push only the feature branch, reply in all six review threads with test evidence, and resolve only addressed threads.**

### Task 16: Versioned PII encryption key rotation

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportCrypto.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/MemberSupportProperties.java`
- Test: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/MemberSupportCryptoTest.java`
- Test: `backend/src/test/java/com/miriyum/global/config/MemberSupportConfigurationTest.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `deploy/.env.example`
- Modify: `deploy/ecs/production-secret-contract.json`
- Modify: `docs/deployment/ecs-production-secret-contract.md`

- [x] **Step 1: Add failing tests for the version prefix, previous-key reads, active-key writes, unknown versions and incomplete previous pairs.**
- [x] **Step 2: Implement authenticated `version || nonce || ciphertext` payloads and active/previous key selection.**
- [x] **Step 3: Document production secret names, rotation order, backup responsibility and irreversible key-loss behavior without committing secret values.**
- [ ] **Step 4: Re-run full unit, deployment-contract, MySQL/HTTP integration, OpenAPI and allowlist verification before publication.**
