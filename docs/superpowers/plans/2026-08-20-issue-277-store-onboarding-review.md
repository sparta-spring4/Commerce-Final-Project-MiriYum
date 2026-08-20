# Issue #277 Store Onboarding Review Workflow Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Require private business-registration evidence and durable automatic checks for every new Store registration, then either auto-finalize or require an assigned Platform Operator decision according to the submission-time review switch.

**Architecture:** Store owns the versioned application, evidence link, automatic-check job, review-case projection, decision, and single Store finalization path. Platform Operator owns review HTTP, #276 assignment/reauthentication authorization, and audit while consuming Store public Service/DTO contracts only. Upload and provider work run outside long Store/application locks; short MySQL transactions use expected versions, unique constraints, idempotency keys, and worker fencing.

**Tech Stack:** Java 21, Spring Boot 4, Spring MVC multipart, Spring Data JPA, Flyway/MySQL 8, Gradle 9.6.1, JUnit 5, AssertJ, Mockito, MockMvc, Testcontainers, OpenAPI 3.1, Redocly.

**Spec:** `docs/superpowers/specs/2026-08-20-issue-277-store-onboarding-review-design.md`

## Global Constraints

- Every future Store registration requires exactly one PDF, JPEG, or PNG business-registration certificate of at most 10,485,760 bytes; MIME and file signature must agree, and encrypted PDFs are rejected.
- Automatic checking is mandatory in both modes. `miriyum.store.onboarding.manual-review-enabled` controls only the immutable `reviewRequired` snapshot of a newly reserved application version.
- Existing approved Stores are not reopened, suspended, or required to upload evidence.
- `Store` is created only by `StoreOnboardingFinalizationService`; automatic and manual approval call the same method.
- Platform Operator code may call Store public Service/DTO contracts but may not import Store onboarding entities, repositories, `FileMetadata`, or `FileStoragePort`.
- Raw evidence, object URLs/keys, complete business numbers, representative names, provider payloads, approvals, tokens, and sessions are forbidden from responses, logs, analytics, and audit JSON.
- `V68__create_store_onboarding_review_workflow.sql` is provisional. Re-fetch `origin/dev` and recheck open migration ownership immediately before implementation and merge; never edit an existing migration.
- Actual S3/IAM/staging activation remains pending on #223. Code completion must not be reported as production activation.
- Local verification runs only focused unit/contract tests and affected integration classes. GitHub CI is authoritative for the full suite; do not run local `build`, `check`, full `integrationTest`, or all integration shards.

---

### Task 1: Freeze policy and OpenAPI contracts

**Files:**
- Modify: `docs/service-policies/02-store-onboarding.md`
- Modify: `docs/service-policies/15-admin-operation.md`
- Modify: `docs/service-policies/17-privacy-security.md`
- Modify: `docs/05-functional-requirements.md`
- Modify: `docs/specs/store-onboarding/spec.md`
- Create: `docs/specs/store-onboarding/openapi.yaml`
- Create: `docs/specs/platform-operator-onboarding-review/openapi.yaml`
- Modify: `docs/specs/store-operator-openapi.yaml`
- Modify: `docs/specs/platform-operator-openapi.yaml`
- Modify: `docs/specs/payment-recovery/openapi.yaml` (approved baseline lint repair: add missing operation summaries only)
- Modify: `docs/specs/README.md`
- Modify: `redocly.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/store/controller/storeoperator/StoreOpenApiContractTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/PlatformOperatorOnboardingOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`

**Interfaces:**
- Consumes: approved design spec and existing `ApiResponse` envelope.
- Produces: multipart Store submission contract; Store Operator application query/supplement contract; Platform Operator case list/detail/assignment/decision/evidence contract; exact schemas used by later Controller tests.

- [x] **Step 1: Write failing contract assertions**

Add exact assertions before creating the new OpenAPI files:

```java
@Test
void storeRegistrationRequiresJsonApplicationAndPrivateEvidence() {
    Map<String, Object> post = operation("/api/v1/store-operators/stores", "post");
    Map<String, Object> multipart = map(map(post.get("requestBody")).get("content"));
    assertThat(multipart).containsKey("multipart/form-data");
    assertThat(map(post.get("responses"))).containsKey("202");
}

@Test
void onboardingDecisionAndEvidencePathsAreInPlatformAudience() {
    assertThat(paths()).containsKeys(
            "/api/v1/platform-operators/onboarding-review-cases/{caseId}/decisions",
            "/api/v1/platform-operators/onboarding-review-cases/{caseId}/evidence");
}
```

- [x] **Step 2: Run the focused contract tests and verify RED**

Run:

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.controller.storeoperator.StoreOpenApiContractTest --tests com.miriyum.domain.platformoperator.onboarding.PlatformOperatorOnboardingOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest
```

Expected: FAIL because the multipart `202` contract and onboarding-review paths do not exist.

- [x] **Step 3: Write the exact policy and OpenAPI contracts**

Use these paths and operations:

```yaml
paths:
  /api/v1/store-operators/stores:
    post:
      operationId: submitStoreOnboardingApplication
      requestBody:
        required: true
        content:
          multipart/form-data:
            schema:
              type: object
              required: [application, businessRegistrationEvidence]
      responses:
        "202":
          description: 입점 신청 접수
  /api/v1/store-operators/onboarding-applications/{applicationId}:
    get:
      operationId: getOwnStoreOnboardingApplication
  /api/v1/store-operators/onboarding-applications/{applicationId}/versions:
    post:
      operationId: submitStoreOnboardingSupplement
```

Platform paths are:

```text
GET  /api/v1/platform-operators/onboarding-review-cases
GET  /api/v1/platform-operators/onboarding-review-cases/{caseId}
POST /api/v1/platform-operators/onboarding-review-cases/{caseId}/assignments
POST /api/v1/platform-operators/onboarding-review-cases/{caseId}/reassignments
POST /api/v1/platform-operators/onboarding-review-cases/{caseId}/decisions
GET  /api/v1/platform-operators/onboarding-review-cases/{caseId}/evidence
```

Decision actions are exactly `APPROVE`, `REJECT`, and `REQUEST_CHANGES`. Store Operator success data requires `applicationId`, `applicationVersion`, `status`, `reviewRequired`, `nextAction`, and nullable `storeId`.

Update policy wording so evidence and automatic checking apply to every future application. Keep only the grandfathering statement for existing approved Stores; do not retain a current no-file registration branch.

- [x] **Step 4: Run contract tests and Redocly checks**

Run:

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.controller.storeoperator.StoreOpenApiContractTest --tests com.miriyum.domain.platformoperator.onboarding.PlatformOperatorOnboardingOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest
cd ..
npx --yes @redocly/cli@2.35.1 lint docs/specs/store-onboarding/openapi.yaml
npx --yes @redocly/cli@2.35.1 lint docs/specs/platform-operator-onboarding-review/openapi.yaml
npx --yes @redocly/cli@2.35.1 lint docs/specs/store-operator-openapi.yaml
npx --yes @redocly/cli@2.35.1 lint docs/specs/platform-operator-openapi.yaml
```

Expected: each command exits 0 with no missing reference or duplicate operation ID. Redocly 2.35.1 on Node 24/Windows may hit a libuv assertion after a successful multi-target lint, so contracts run separately.

- [x] **Step 5: Update Issue #277 after explicit GitHub-write authorization**

Add these contract bullets to the Issue body without closing it:

```markdown
- 모든 향후 신규 매장 신청은 사업자등록증과 자동검사가 필수다.
- `manual-review-enabled`는 자동검사 통과 뒤 플랫폼 운영자 심사를 추가할지만 신청 version에 snapshot한다.
- 구현 완료는 운영 활성화 완료가 아니다. 실제 private S3/IAM/staging 신청·일회 열람 검증은 #223 완료가 필수다.
- migration: `V68__create_store_onboarding_review_workflow.sql` (구현·병합 직전 충돌 재확인)
- exact allowlist: 이 구현 계획의 각 Task `Files` 합집합으로 제한한다.
```

- [ ] **Step 6: Commit the contract slice after explicit commit authorization**

```powershell
git add -- docs/service-policies/02-store-onboarding.md docs/service-policies/15-admin-operation.md docs/service-policies/17-privacy-security.md docs/05-functional-requirements.md docs/specs/store-onboarding/spec.md docs/specs/store-onboarding/openapi.yaml docs/specs/platform-operator-onboarding-review/openapi.yaml docs/specs/store-operator-openapi.yaml docs/specs/platform-operator-openapi.yaml docs/specs/payment-recovery/openapi.yaml docs/specs/README.md redocly.yaml backend/src/test/java/com/miriyum/domain/store/controller/storeoperator/StoreOpenApiContractTest.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/PlatformOperatorOnboardingOpenApiContractTest.java backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java
git commit -m "docs: define store onboarding review contract"
```

### Task 2: Add the versioned MySQL ledger and aggregate rules

**Files:**
- Create: `backend/src/main/resources/db/migration/V68__create_store_onboarding_review_workflow.sql`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingEnums.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingApplication.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingApplicationVersion.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingAutomaticCheckJob.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingReviewCase.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingDecision.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingApplicationRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingApplicationVersionRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingAutomaticCheckJobRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingReviewCaseRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingDecisionRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingApplicationTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingReviewCaseTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/StoreOnboardingMigrationIT.java`

**Interfaces:**
- Consumes: V67 evidence ledger identifiers and existing `stores.store_id`.
- Produces: `StoreOnboardingApplication.reserve(...)`, `attachVersion(...)`, `beginReview(...)`, `requestChanges(...)`, `approve(...)`, `reject(...)`; fenced job claims; one active case and one terminal decision constraints.

- [ ] **Step 1: Write failing aggregate tests**

```java
@Test
void supplementInvalidatesTheOldVersion() {
    StoreOnboardingApplication app = StoreOnboardingApplication.reserve(
            11L, "key", "fingerprint", true, NOW);
    app.beginEvidenceUpload(1L);
    app.attachVersion(1L, NOW);
    app.requestChanges(1L, NOW);

    long next = app.reserveSupplement(1L, "next-key", "next-fingerprint", NOW);

    assertThat(next).isEqualTo(2L);
    assertThat(app.getCurrentVersion()).isEqualTo(2L);
    assertThat(app.getStatus()).isEqualTo(ApplicationStatus.EVIDENCE_PENDING);
}

@Test
void firstTerminalDecisionWins() {
    StoreOnboardingReviewCase reviewCase = reviewReadyCase();
    reviewCase.assign(1L, 91L, NOW);
    reviewCase.approve(2L, NOW);
    assertThatThrownBy(() -> reviewCase.reject(2L, NOW))
            .isInstanceOf(IllegalStateException.class);
}
```

- [ ] **Step 2: Run entity tests and verify RED**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.onboarding.entity.StoreOnboardingApplicationTest --tests com.miriyum.domain.store.onboarding.entity.StoreOnboardingReviewCaseTest
```

Expected: compilation FAIL because the onboarding entities do not exist.

- [ ] **Step 3: Implement minimal aggregates and repositories**

Define nested enums exactly:

```java
public final class StoreOnboardingEnums {
    public enum ApplicationStatus {
        RECEIVED, EVIDENCE_PENDING, AUTO_CHECKING, REVIEW_READY, UNDER_REVIEW,
        CHANGES_REQUESTED, AUTO_APPROVED, APPROVED, REJECTED
    }
    public enum AutomaticCheckStatus { PENDING, PROCESSING, PASSED, REJECTED, EXHAUSTED }
    public enum ReviewCaseType { ONBOARDING, OWNERSHIP_CONFLICT }
    public enum ReviewCaseStatus { REVIEW_READY, UNDER_REVIEW, CHANGES_REQUESTED, APPROVED, REJECTED, CLOSED }
    public enum DecisionType { APPROVE, REJECT, REQUEST_CHANGES }
}
```

The application reservation stores `(store_operator_account_id, submission_idempotency_key, submission_fingerprint)` with a unique key on owner plus idempotency key and starts in `RECEIVED`. `beginEvidenceUpload` moves the reserved current version to `EVIDENCE_PENDING`. `reserveSupplement` accepts only the current `CHANGES_REQUESTED` version. `attachVersion` accepts only `EVIDENCE_PENDING`. `approve` requires `AUTO_CHECKING` with a passed job for auto mode or `UNDER_REVIEW` for manual mode.

Add MySQL constraints including:

```sql
CONSTRAINT uk_store_onboarding_submission UNIQUE
    (store_operator_account_id, submission_idempotency_key),
CONSTRAINT uk_store_onboarding_version UNIQUE
    (store_onboarding_application_id, application_version),
CONSTRAINT uk_store_onboarding_active_case UNIQUE
    (store_onboarding_application_id, application_version, active_marker),
CONSTRAINT uk_store_onboarding_terminal_decision UNIQUE
    (case_public_id, case_version),
CONSTRAINT uk_store_onboarding_result_store UNIQUE (resulting_store_id)
```

Review and decision rows use opaque UUID strings. Job rows include `lease_owner`, monotonically increasing `lease_token`, `lease_expires_at`, `attempt_count`, `next_attempt_at`, and `row_version`.

- [ ] **Step 4: Add and run focused migration tests**

The integration test inserts two active cases for the same application/version and expects the second insert to fail, then inserts one `active_marker=NULL` historical row and expects success.

```powershell
cd backend
.\gradlew.bat integrationTest --tests com.miriyum.domain.store.onboarding.StoreOnboardingMigrationIT
```

Expected: PASS against actual MySQL Testcontainers.

- [ ] **Step 5: Commit the persistence slice after explicit authorization**

```powershell
git add -- backend/src/main/resources/db/migration/V68__create_store_onboarding_review_workflow.sql backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingEnums.java backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingApplication.java backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingApplicationVersion.java backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingAutomaticCheckJob.java backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingReviewCase.java backend/src/main/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingDecision.java backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingApplicationRepository.java backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingApplicationVersionRepository.java backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingAutomaticCheckJobRepository.java backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingReviewCaseRepository.java backend/src/main/java/com/miriyum/domain/store/onboarding/repository/StoreOnboardingDecisionRepository.java backend/src/test/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingApplicationTest.java backend/src/test/java/com/miriyum/domain/store/onboarding/entity/StoreOnboardingReviewCaseTest.java backend/src/test/java/com/miriyum/domain/store/onboarding/StoreOnboardingMigrationIT.java
git commit -m "feat: add store onboarding workflow ledger"
```

### Task 3: Validate and store private registration evidence

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/evidence/ValidatedBusinessRegistrationEvidence.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/evidence/BusinessRegistrationEvidenceUploadValidator.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/evidence/BusinessRegistrationEvidenceUploadService.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/evidence/dto/BusinessRegistrationEvidenceContent.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/evidence/StoreBusinessRegistrationEvidenceService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/evidence/StoreOnboardingEvidenceConfiguration.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/error/StoreErrorCode.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/evidence/BusinessRegistrationEvidenceUploadValidatorTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/evidence/BusinessRegistrationEvidenceUploadServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/evidence/StoreBusinessRegistrationEvidenceServiceTest.java`

**Interfaces:**
- Consumes: `FileStorageFacade.storePending`, `confirmWithinCurrentTransaction`, `FileStoragePort.read`, and #344 evidence ledger.
- Produces: `ValidatedBusinessRegistrationEvidence validate(MultipartFile)`; `PendingEvidence storePending(long applicationId, long version, MultipartFile)`; `BusinessRegistrationEvidenceContent readCurrentEvidence(long applicationId, long version, UUID evidenceId)`. `PendingEvidence` is `record PendingEvidence(UUID fileId, String sha256, String contentType, long sizeBytes)`.

- [ ] **Step 1: Write failing validator tests**

```java
@ParameterizedTest
@MethodSource("validDocuments")
void acceptsSupportedSignatures(String contentType, byte[] bytes) {
    var result = validator.validate(file(contentType, bytes));
    assertThat(result.contentType()).isEqualTo(contentType);
}

@Test
void rejectsEncryptedPdf() {
    byte[] encrypted = "%PDF-1.7\n1 0 obj<</Encrypt 2 0 R>>".getBytes(UTF_8);
    assertThatThrownBy(() -> validator.validate(file("application/pdf", encrypted)))
            .isInstanceOf(ServiceException.class);
}
```

Valid signatures are `%PDF-`, JPEG `FF D8 FF`, and PNG `89 50 4E 47 0D 0A 1A 0A`. Test exactly 10,485,760 bytes as accepted and 10,485,761 as rejected.

- [ ] **Step 2: Run evidence tests and verify RED**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadValidatorTest --tests com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadServiceTest
```

Expected: compilation FAIL because validator and upload service are absent.

- [ ] **Step 3: Implement private pending upload and integrity-checked read**

Use stable owner and object identity:

```java
FileStorageOwner owner = new FileStorageOwner("STORE_ONBOARDING_APPLICATION", applicationId);
String objectKey = "private/store-onboarding/" + applicationId + "/versions/"
        + applicationVersion + "/" + validated.sha256();
FileStorageMetadata metadata = new FileStorageMetadata(
        stableFileId(applicationId, applicationVersion, validated.sha256()),
        owner, FileStoragePurpose.BUSINESS_LICENSE, objectKey,
        validated.contentType(), validated.bytes().length, validated.sha256(),
        FileStorageVisibility.PRIVATE, FileStorageStatus.PENDING,
        "STORE_ONBOARDING_PRIVATE", clock.instant(), null);
```

`readCurrentEvidence` loads the current evidence internally, resolves its private confirmed metadata, calls `FileStoragePort.read`, and compares object key, MIME, byte length, and SHA-256 before returning only content type and copied bytes. No public DTO exposes `fileId` or object key.

- [ ] **Step 4: Run evidence tests**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadValidatorTest --tests com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadServiceTest --tests com.miriyum.domain.store.evidence.StoreBusinessRegistrationEvidenceServiceTest
```

Expected: PASS; assertions verify private visibility and response/log objects contain no URL or object key.

- [ ] **Step 5: Commit the evidence slice after explicit authorization**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/evidence/ValidatedBusinessRegistrationEvidence.java backend/src/main/java/com/miriyum/domain/store/evidence/BusinessRegistrationEvidenceUploadValidator.java backend/src/main/java/com/miriyum/domain/store/evidence/BusinessRegistrationEvidenceUploadService.java backend/src/main/java/com/miriyum/domain/store/evidence/dto/BusinessRegistrationEvidenceContent.java backend/src/main/java/com/miriyum/domain/store/evidence/StoreBusinessRegistrationEvidenceService.java backend/src/main/java/com/miriyum/domain/store/evidence/StoreOnboardingEvidenceConfiguration.java backend/src/main/java/com/miriyum/domain/store/error/StoreErrorCode.java backend/src/test/java/com/miriyum/domain/store/evidence/BusinessRegistrationEvidenceUploadValidatorTest.java backend/src/test/java/com/miriyum/domain/store/evidence/BusinessRegistrationEvidenceUploadServiceTest.java backend/src/test/java/com/miriyum/domain/store/evidence/StoreBusinessRegistrationEvidenceServiceTest.java
git commit -m "feat: add private onboarding evidence upload"
```

### Task 4: Replace immediate Store creation with durable application submission

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/config/StoreOnboardingProperties.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/dto/StoreOnboardingContracts.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingRequestFingerprint.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingTransactionExecutor.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingSubmissionService.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingQueryService.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/service/StoreGeocodingService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/controller/storeoperator/StoreController.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/controller/storeoperator/StoreOnboardingApplicationController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/dto/storeoperator/StoreCreateRequest.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/dto/storeoperator/StoreOnboardingApplicationResponse.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreCommandFingerprint.java`
- Modify: `backend/src/main/resources/application.yml`
- Modify: `backend/src/test/java/com/miriyum/domain/store/controller/storeoperator/StoreControllerTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingSubmissionServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingQueryServiceTest.java`

**Interfaces:**
- Consumes: Task 2 repositories, Task 3 upload service, existing catalog/geocoding validation, Store Operator account guard.
- Produces: `IdempotentOutcome submit(long operatorId, IdempotencyKey key, StoreCreateRequest request, MultipartFile evidence)`; `IdempotentOutcome supplement(long operatorId, long applicationId, IdempotencyKey key, StoreCreateRequest request, MultipartFile evidence)`; `ApplicationData getOwn(long operatorId, long applicationId)`; concrete `StoreOnboardingApplicationOwnershipPort` bean.

- [ ] **Step 1: Write failing multipart Controller and submission tests**

```java
mockMvc.perform(multipart("/api/v1/store-operators/stores")
        .file(jsonPart("application", validRequestJson()))
        .file(pdfPart())
        .header("Authorization", bearer)
        .header("Idempotency-Key", KEY))
    .andExpect(status().isAccepted())
    .andExpect(jsonPath("$.data.applicationVersion").value(1))
    .andExpect(jsonPath("$.data.status").value("AUTO_CHECKING"));
```

Service tests assert the same key and fingerprint returns the same application, while the same key with a different certificate checksum throws `COMMON_007`.

- [ ] **Step 2: Run submission tests and verify RED**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.controller.storeoperator.StoreControllerTest --tests com.miriyum.domain.store.onboarding.service.StoreOnboardingSubmissionServiceTest --tests com.miriyum.domain.store.onboarding.service.StoreOnboardingQueryServiceTest
```

Expected: FAIL because `StoreController` still accepts JSON and immediately returns a Store.

- [ ] **Step 3: Implement reserve-upload-attach transactions**

`StoreOnboardingSubmissionService` reads and validates the multipart bytes first, performs catalog/geocoding preflight without a Store/application lock, then executes:

```java
ReservedApplication reserved = transactions.reserve(operatorId, key, fingerprint, reviewEnabled);
transactions.beginEvidenceUpload(reserved.applicationId(), reserved.applicationVersion());
PendingEvidence pending = evidenceUploads.storePending(
        reserved.applicationId(), reserved.applicationVersion(), file);
return transactions.attachAndSubmit(reserved, pending, request, verifiedGeocoding);
```

`reserve` replays `(owner,key,fingerprint)` and rejects changed fingerprints. `attachAndSubmit` confirms the pending file in the current short transaction, calls `replaceCurrentEvidence`, inserts the immutable version snapshot and one pending automatic-check job, then returns `202` data. `StoreGeocodingService` contains the existing `StoreService` preflight logic so both onboarding and Store update use one validator.

Extend `StoreCreateRequest` with required `legalBusinessName`, `representativeName`, `openingDate`, `primaryBusinessCategory`, and `primaryBusinessItem`. The request fingerprint includes normalized JSON fields plus evidence SHA-256; it never includes raw evidence bytes.

Define the public Store boundary in `StoreOnboardingContracts` with these exact records:

```java
public record ReservedApplication(long applicationId, long applicationVersion,
        boolean reviewRequired) {}
public record ApplicationData(long applicationId, long applicationVersion,
        ApplicationStatus status, boolean reviewRequired, String nextAction, String storeId) {}
public record ReviewDecisionCommand(String caseId, long expectedCaseVersion,
        long expectedApplicationVersion, DecisionType action, String reasonCode,
        AdminAuditContext context, String idempotencyKey) {}
public record EvidenceReadQuery(String caseId, long expectedCaseVersion,
        long applicationId, long applicationVersion, UUID evidenceId) {}
public record ReviewCaseQuery(ReviewCaseStatus status, ReviewCaseType type,
        int page, int size) {}
public record ReviewCaseSummary(String caseId, ReviewCaseType type,
        ReviewCaseStatus status, long applicationId, long applicationVersion,
        long caseVersion, String maskedBusinessNumber, Instant receivedAt) {}
public record ReviewCasePage(List<ReviewCaseSummary> content, int page,
        int size, long totalElements, int totalPages) {}
public record ReviewCaseDetail(String caseId, ReviewCaseType type,
        ReviewCaseStatus status, long applicationId, long applicationVersion,
        long caseVersion, Long assignedOperatorId, ApplicationData application) {}
```

Properties are exact:

```yaml
miriyum:
  store:
    onboarding:
      manual-review-enabled: false
      automatic-check-delay-ms: 5000
      automatic-check-initial-delay-ms: 30000
      automatic-check-batch-size: 25
      automatic-check-lease-seconds: 30
      case-assignment-days: 3650
```

- [ ] **Step 4: Run submission/query tests**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.controller.storeoperator.StoreControllerTest --tests com.miriyum.domain.store.onboarding.service.StoreOnboardingSubmissionServiceTest --tests com.miriyum.domain.store.onboarding.service.StoreOnboardingQueryServiceTest --tests com.miriyum.domain.store.service.StoreServiceTest
```

Expected: PASS; no test expects immediate Store creation from POST `/stores`.

- [ ] **Step 5: Commit the submission slice after explicit authorization**

Stage the exact Task 4 paths and commit:

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/onboarding/config/StoreOnboardingProperties.java backend/src/main/java/com/miriyum/domain/store/onboarding/dto/StoreOnboardingContracts.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingRequestFingerprint.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingTransactionExecutor.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingSubmissionService.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingQueryService.java backend/src/main/java/com/miriyum/domain/store/service/StoreGeocodingService.java backend/src/main/java/com/miriyum/domain/store/controller/storeoperator/StoreController.java backend/src/main/java/com/miriyum/domain/store/controller/storeoperator/StoreOnboardingApplicationController.java backend/src/main/java/com/miriyum/domain/store/dto/storeoperator/StoreCreateRequest.java backend/src/main/java/com/miriyum/domain/store/dto/storeoperator/StoreOnboardingApplicationResponse.java backend/src/main/java/com/miriyum/domain/store/service/StoreService.java backend/src/main/java/com/miriyum/domain/store/service/StoreCommandFingerprint.java backend/src/main/resources/application.yml backend/src/test/java/com/miriyum/domain/store/controller/storeoperator/StoreControllerTest.java backend/src/test/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingSubmissionServiceTest.java backend/src/test/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingQueryServiceTest.java
git commit -m "feat: submit durable store onboarding applications"
```

### Task 5: Run durable automatic checks and one finalization path

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/BusinessRegistrationVerificationPort.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/MockBusinessRegistrationVerificationAdapter.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingAutomaticCheckTransaction.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingAutomaticCheckJob.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingFinalizationService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/repository/StoreRepository.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/service/MockBusinessRegistrationVerificationAdapterTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingAutomaticCheckJobTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingFinalizationServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/StoreOnboardingAutomaticCheckConcurrencyIT.java`

**Interfaces:**
- Consumes: immutable application snapshot and Task 2 fenced job repository.
- Produces: `VerificationResult verify(VerificationRequest)`; `Optional<Claim> claim(String owner)`; `void record(Claim, VerificationResult)`; `long finalizeApproved(long applicationId, long version, ApprovalMode mode)`.

- [ ] **Step 1: Write failing worker and finalization tests**

```java
@Test
void passedCheckAutoFinalizesOnlyWhenReviewWasNotRequired() {
    given(reviewRequired(false));
    when(job.runOnce("worker-a"));
    then(applicationStatus()).isEqualTo(AUTO_APPROVED);
    then(resultingStoreCount()).isEqualTo(1);
}

@Test
void passedCheckCreatesReviewCaseWhenReviewWasRequired() {
    given(reviewRequired(true));
    when(job.runOnce("worker-a"));
    then(applicationStatus()).isEqualTo(REVIEW_READY);
    then(activeReviewCaseCount()).isEqualTo(1);
    then(resultingStoreCount()).isZero();
}
```

Add tests for definitive rejection, retryable outage, five-attempt exhaustion, expired claim recovery, and stale fencing-token rejection.

- [ ] **Step 2: Run automatic-check tests and verify RED**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.onboarding.service.MockBusinessRegistrationVerificationAdapterTest --tests com.miriyum.domain.store.onboarding.service.StoreOnboardingAutomaticCheckJobTest --tests com.miriyum.domain.store.onboarding.service.StoreOnboardingFinalizationServiceTest
```

Expected: compilation FAIL because the port, worker, and finalizer are absent.

- [ ] **Step 3: Implement provider result and worker transitions**

Use exact public result types:

```java
public interface BusinessRegistrationVerificationPort {
    VerificationResult verify(VerificationRequest request);
    record VerificationRequest(String businessNumber, String legalName,
            String representativeName, LocalDate openingDate,
            String primaryCategory, String primaryItem, String policyVersion) {}
    record VerificationResult(Outcome outcome, String reasonCode, String providerVersion) {}
    enum Outcome { PASSED, REJECTED, RETRYABLE_FAILURE }
}
```

The mock returns `PASSED` only after the same format/nonblank rules used by the request contract; tests inject a fake port for rejected and retryable outcomes. Backoff is exactly 1 minute, 5 minutes, 30 minutes, 2 hours, and 12 hours. After five retryable failures, mark the job `EXHAUSTED`, retain application status `AUTO_CHECKING`, and expose a masked manual-operations next action.

`StoreOnboardingFinalizationService` locks application/version, verifies the passed automatic job and expected approval mode, checks `StoreRepository.existsByBusinessRegistrationNumber`, creates `Store.createVerified(...)`, saves/flushes it, and records `resultingStoreId` in the same transaction.

- [ ] **Step 4: Run unit and focused MySQL concurrency tests**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.store.onboarding.service.MockBusinessRegistrationVerificationAdapterTest --tests com.miriyum.domain.store.onboarding.service.StoreOnboardingAutomaticCheckJobTest --tests com.miriyum.domain.store.onboarding.service.StoreOnboardingFinalizationServiceTest
.\gradlew.bat integrationTest --tests com.miriyum.domain.store.onboarding.StoreOnboardingAutomaticCheckConcurrencyIT
```

Expected: PASS; two workers or two finalizers converge to one Store and one terminal result.

- [ ] **Step 5: Commit the automatic-check slice after explicit authorization**

Stage the exact Task 5 paths and commit:

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/onboarding/service/BusinessRegistrationVerificationPort.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/MockBusinessRegistrationVerificationAdapter.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingAutomaticCheckTransaction.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingAutomaticCheckJob.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingFinalizationService.java backend/src/main/java/com/miriyum/domain/store/repository/StoreRepository.java backend/src/test/java/com/miriyum/domain/store/onboarding/service/MockBusinessRegistrationVerificationAdapterTest.java backend/src/test/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingAutomaticCheckJobTest.java backend/src/test/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingFinalizationServiceTest.java backend/src/test/java/com/miriyum/domain/store/onboarding/StoreOnboardingAutomaticCheckConcurrencyIT.java
git commit -m "feat: automate store onboarding verification"
```

### Task 6: Add Platform Operator review, assignment, and decisions

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/dto/OnboardingReviewRequests.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/dto/OnboardingReviewResponses.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/service/OnboardingReviewQueryService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/service/OnboardingReviewCommandService.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/service/OnboardingAuditSnapshots.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/controller/PlatformOperatorOnboardingReviewController.java`
- Create: `backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingReviewWorkflow.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/HighRiskCommandGuard.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/AdminCommandPurpose.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditAction.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditReason.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriter.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingReviewQueryServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingReviewCommandServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/PlatformOperatorOnboardingReviewControllerTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingDecisionConcurrencyIT.java`

**Interfaces:**
- Consumes: Store public `StoreOnboardingReviewWorkflow`, #276 guard/assignment, Platform Operator principal/audit.
- Produces: list/detail, initial self-assignment, explicit reassignment, and one `decide` command for three actions.

- [ ] **Step 1: Write failing authorization and concurrency tests**

```java
@Test
void approveRequiresPermissionAssignmentVersionsAndReauthentication() {
    assertThatThrownBy(() -> commands.decide(commandWithoutAssignment()))
            .isInstanceOfSatisfying(ServiceException.class,
                    error -> assertThat(error.getErrorCode().getCode()).isEqualTo("ADMIN_001"));
}

@Test
void concurrentApproveAndRejectHaveOneWinner() {
    runConcurrently(this::approve, this::reject);
    assertThat(decisions.countByCaseId(CASE_ID)).isEqualTo(1);
    assertThat(stores.count()).isEqualTo(1);
}
```

- [ ] **Step 2: Run review tests and verify RED**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.platformoperator.onboarding.OnboardingReviewQueryServiceTest --tests com.miriyum.domain.platformoperator.onboarding.OnboardingReviewCommandServiceTest --tests com.miriyum.domain.platformoperator.onboarding.PlatformOperatorOnboardingReviewControllerTest
```

Expected: compilation FAIL because review services/controllers do not exist.

- [ ] **Step 3: Implement the review public boundary and guard calls**

Add `ONBOARDING_ASSIGNMENT` and `ONBOARDING_EVIDENCE_ACCESS` purposes. Add `HighRiskCommandGuard.authorizeInitialOnboardingAssignment(...)`, constrained to `ONBOARDING_REVIEW`, `ONBOARDING_ASSIGNMENT`, `ONBOARDING_APPLICATION`, and `ONBOARDING_REVIEW` permission while skipping only the not-yet-existing assignment check.

Decision request is exact:

```java
public record DecisionRequest(
        @NotNull DecisionType action,
        @Min(1) long expectedApplicationVersion,
        @Min(1) long expectedCaseVersion,
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String reasonCode) {}
```

`OnboardingReviewCommandService` starts one transaction, runs the guard, and calls:

```java
workflow.decide(new ReviewDecisionCommand(
        caseId, request.expectedCaseVersion(), request.expectedApplicationVersion(),
        request.action(), request.reasonCode(), context, idempotencyKey));
```

`StoreOnboardingReviewWorkflow` is the Store-owned public interface:

```java
public interface StoreOnboardingReviewWorkflow {
    ReviewCasePage listReviewCases(ReviewCaseQuery query);
    ReviewCaseDetail getReviewCase(String caseId);
    ReviewCaseDetail assign(String caseId, long expectedCaseVersion,
            long operatorId, Instant expiresAt);
    ReviewCaseDetail reassign(String caseId, long expectedCaseVersion,
            long currentOperatorId, long nextOperatorId, Instant expiresAt);
    ApplicationData decide(ReviewDecisionCommand command);
    BusinessRegistrationEvidenceContent readEvidence(EvidenceReadQuery query);
}
```

APPROVE calls the Task 5 finalizer. REQUEST_CHANGES closes the active assignment and old version. REJECT records the terminal state without creating a Store. Initial assignment uses a 3,650-day expiry and no renewal endpoint; reassignment is explicit and requires the current assignment.

- [ ] **Step 4: Run unit and concurrency tests**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.platformoperator.onboarding.OnboardingReviewQueryServiceTest --tests com.miriyum.domain.platformoperator.onboarding.OnboardingReviewCommandServiceTest --tests com.miriyum.domain.platformoperator.onboarding.PlatformOperatorOnboardingReviewControllerTest --tests com.miriyum.domain.platformoperator.service.HighRiskCommandGuardTest --tests com.miriyum.domain.platformoperator.service.PlatformOperatorAuditWriterTest
.\gradlew.bat integrationTest --tests com.miriyum.domain.platformoperator.onboarding.OnboardingDecisionConcurrencyIT
```

Expected: PASS; concurrent decisions persist one immutable decision, one audit outcome, and at most one Store.

- [ ] **Step 5: Commit the review slice after explicit authorization**

Stage the exact Task 6 paths and commit:

```powershell
git add -- backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/dto/OnboardingReviewRequests.java backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/dto/OnboardingReviewResponses.java backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/service/OnboardingReviewQueryService.java backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/service/OnboardingReviewCommandService.java backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/service/OnboardingAuditSnapshots.java backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/controller/PlatformOperatorOnboardingReviewController.java backend/src/main/java/com/miriyum/domain/store/onboarding/service/StoreOnboardingReviewWorkflow.java backend/src/main/java/com/miriyum/domain/platformoperator/service/HighRiskCommandGuard.java backend/src/main/java/com/miriyum/domain/platformoperator/enums/AdminCommandPurpose.java backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditAction.java backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditReason.java backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriter.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingReviewQueryServiceTest.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingReviewCommandServiceTest.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/PlatformOperatorOnboardingReviewControllerTest.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingDecisionConcurrencyIT.java
git commit -m "feat: review store onboarding applications"
```

### Task 7: Add assigned five-minute one-time evidence access

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/service/OnboardingEvidenceAccessService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/controller/PlatformOperatorOnboardingReviewController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriter.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditAction.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditReason.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingEvidenceAccessServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingEvidenceAuditPrivacyTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingEvidenceAccessHttpIT.java`

**Interfaces:**
- Consumes: common reauthentication approval, current assignment, `ONBOARDING_EVIDENCE_READ`, and Store `readCurrentEvidence` boundary.
- Produces: stable `GET .../{caseId}/evidence` response with content bytes and no S3 URL; REQUIRES_NEW access-attempt audit.

- [ ] **Step 1: Write failing one-time and privacy tests**

```java
mockMvc.perform(get(EVIDENCE_PATH, caseId)
        .header("Authorization", bearer)
        .header("X-Admin-Reauthentication", approval)
        .header("X-Correlation-Id", "corr-evidence-1"))
    .andExpect(status().isOk())
    .andExpect(header().string("Content-Type", "application/pdf"));

mockMvc.perform(get(EVIDENCE_PATH, caseId)
        .header("Authorization", bearer)
        .header("X-Admin-Reauthentication", approval)
        .header("X-Correlation-Id", "corr-evidence-2"))
    .andExpect(status().isForbidden());
```

Assert response and audit serialization do not contain `objectKey`, `fileId`, `businessRegistrationNumber`, representative name, or approval value.

- [ ] **Step 2: Run evidence-access tests and verify RED**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.platformoperator.onboarding.OnboardingEvidenceAccessServiceTest --tests com.miriyum.domain.platformoperator.onboarding.OnboardingEvidenceAuditPrivacyTest
```

Expected: compilation FAIL because `OnboardingEvidenceAccessService` is absent.

- [ ] **Step 3: Implement consume-then-read ordering**

`authorizeAccess` runs in a short transaction and consumes reauthentication through `HighRiskCommandGuard.authorize` with target ID `applicationId:applicationVersion:evidenceId`. It commits before storage read. The subsequent read cannot roll back approval consumption.

On success or failure, call a dedicated `PlatformOperatorAuditWriter.appendOnboardingReadAttempt(...)` with `Propagation.REQUIRES_NEW` and `AdminCaseType.ONBOARDING_REVIEW`. Store only public IDs, versions, actor/authority snapshot, outcome, allowlisted reason, and correlation ID.

- [ ] **Step 4: Run unit and focused HTTP integration tests**

```powershell
cd backend
.\gradlew.bat test --tests com.miriyum.domain.platformoperator.onboarding.OnboardingEvidenceAccessServiceTest --tests com.miriyum.domain.platformoperator.onboarding.OnboardingEvidenceAuditPrivacyTest
.\gradlew.bat integrationTest --tests com.miriyum.domain.platformoperator.onboarding.OnboardingEvidenceAccessHttpIT
```

Expected: PASS for first read, reuse denial, stale-version denial, reassignment denial, and storage failure with consumed approval plus failure audit.

- [ ] **Step 5: Commit the evidence-access slice after explicit authorization**

Stage the exact Task 7 paths and commit:

```powershell
git add -- backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/service/OnboardingEvidenceAccessService.java backend/src/main/java/com/miriyum/domain/platformoperator/onboarding/controller/PlatformOperatorOnboardingReviewController.java backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorAuditWriter.java backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditAction.java backend/src/main/java/com/miriyum/domain/platformoperator/enums/PlatformOperatorAuditReason.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingEvidenceAccessServiceTest.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingEvidenceAuditPrivacyTest.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/OnboardingEvidenceAccessHttpIT.java
git commit -m "feat: secure onboarding evidence access"
```

### Task 8: Close HTTP, feature, privacy, and collision verification

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/store/onboarding/StoreOnboardingHttpIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/PlatformOperatorOnboardingHttpIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/config/PlatformOperatorFeatureFlagIT.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/ApiUrlConventionTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/controller/storeoperator/StoreOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/repository/StoreRepositoryIT.java`
- Modify: `backend/ai/implementation-guardrails.md`

**Interfaces:**
- Consumes: all prior tasks.
- Produces: end-to-end proof for mandatory evidence/checks, switch snapshot, automatic/manual finalization, stale commands, privacy, feature-off admin 404, package boundaries, and exact local verification evidence.

- [ ] **Step 1: Write missing end-to-end assertions**

Cover these scenarios as separate tests:

```text
manual-review=false + passed check -> one Store, AUTO_APPROVED
manual-review=true + passed check -> REVIEW_READY, zero Store until decision
switch changes after submission -> in-flight reviewRequired unchanged
supplement creates version 2 -> version 1 assignment/decision/evidence approval rejected
concurrent approve/reject -> one winner
duplicate business number -> existing Store unchanged
platform-operator.enabled=false -> review Controller and OpenAPI path absent
consumer/store-operator credential -> Platform Operator review unauthorized
```

- [ ] **Step 2: Run focused HTTP, feature, architecture, and repository tests**

```powershell
cd backend
.\gradlew.bat integrationTest --tests com.miriyum.domain.store.onboarding.StoreOnboardingHttpIT --tests com.miriyum.domain.platformoperator.onboarding.PlatformOperatorOnboardingHttpIT --tests com.miriyum.domain.platformoperator.config.PlatformOperatorFeatureFlagIT
.\gradlew.bat test --tests com.miriyum.architecture.DomainPackageArchitectureTest --tests com.miriyum.architecture.ApiUrlConventionTest --tests com.miriyum.domain.platformoperator.PlatformOperatorOpenApiContractTest --tests com.miriyum.domain.store.controller.storeoperator.StoreOpenApiContractTest
.\gradlew.bat integrationTest --tests com.miriyum.domain.store.repository.StoreRepositoryIT
```

Expected: PASS with no forbidden package import or OpenAPI drift.

- [ ] **Step 3: Recheck migration and exact allowlist**

```powershell
git fetch origin dev:refs/remotes/origin/dev
Get-ChildItem backend/src/main/resources/db/migration -File | Sort-Object Name | Select-Object -Last 10 -ExpandProperty Name
git diff --name-only origin/dev...HEAD
```

Expected: V68 remains free or is renumbered consistently in migration, spec, plan, Issue #277, and tests. Every changed path appears in a Task `Files` list or is the approved design/plan document.

- [ ] **Step 4: Run final focused verification and patch checks**

```powershell
cd backend
.\gradlew.bat test --tests 'com.miriyum.domain.store.onboarding.*' --tests 'com.miriyum.domain.platformoperator.onboarding.*' --tests com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadValidatorTest --tests com.miriyum.domain.store.evidence.BusinessRegistrationEvidenceUploadServiceTest
.\gradlew.bat integrationTest --tests com.miriyum.domain.store.onboarding.StoreOnboardingMigrationIT --tests com.miriyum.domain.store.onboarding.StoreOnboardingAutomaticCheckConcurrencyIT --tests com.miriyum.domain.store.onboarding.StoreOnboardingHttpIT --tests com.miriyum.domain.platformoperator.onboarding.OnboardingDecisionConcurrencyIT --tests com.miriyum.domain.platformoperator.onboarding.OnboardingEvidenceAccessHttpIT --tests com.miriyum.domain.platformoperator.onboarding.PlatformOperatorOnboardingHttpIT
cd ..
pnpm exec redocly lint docs/specs/store-onboarding/openapi.yaml docs/specs/platform-operator-onboarding-review/openapi.yaml docs/specs/store-operator-openapi.yaml docs/specs/platform-operator-openapi.yaml
git diff --check origin/dev...HEAD
```

Expected: all focused tests and lint pass. Report actual S3/IAM/staging and full regression as pending on #223/GitHub CI rather than running the full local suite.

- [ ] **Step 5: Commit the final verification slice after explicit authorization**

Stage the exact Task 8 paths and commit:

```powershell
git add -- backend/src/test/java/com/miriyum/domain/store/onboarding/StoreOnboardingHttpIT.java backend/src/test/java/com/miriyum/domain/platformoperator/onboarding/PlatformOperatorOnboardingHttpIT.java backend/src/test/java/com/miriyum/domain/platformoperator/config/PlatformOperatorFeatureFlagIT.java backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java backend/src/test/java/com/miriyum/architecture/ApiUrlConventionTest.java backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java backend/src/test/java/com/miriyum/domain/store/controller/storeoperator/StoreOpenApiContractTest.java backend/src/test/java/com/miriyum/domain/store/repository/StoreRepositoryIT.java backend/ai/implementation-guardrails.md
git commit -m "test: verify store onboarding review workflow"
```

## Exact Change Allowlist

Runtime implementation is limited to the union of the exact paths under Tasks 1 through 8, plus:

- `docs/superpowers/specs/2026-08-20-issue-277-store-onboarding-review-design.md`
- `docs/superpowers/plans/2026-08-20-issue-277-store-onboarding-review.md`
- `docs/specs/payment-recovery/openapi.yaml` (2026-08-21 user-approved Redocly baseline repair; operation summaries only)

If implementation requires another production, test, migration, configuration, or canonical-document path, stop before creating or modifying it, update the plan and Issue #277 allowlist after user approval, and then continue.
