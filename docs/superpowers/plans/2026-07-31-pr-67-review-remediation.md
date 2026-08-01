# PR #67 Store Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the seven verified Store review gaps in PR #67 without implementing the deferred OPER-009 close-store command.

**Architecture:** Store core keeps ownership of onboarding evidence, permanent business-number ownership, reversible general updates, and purpose-specific authority guards. Store schedule consumes only those public guards, performs mutable publication checks inside the idempotency callback, and keeps immutable schedule entities behind private constructors and static factories.

**Tech Stack:** Java 21, Spring Boot 4.1.0, Jakarta Bean Validation, Spring MVC MockMvc, JPA, Flyway, MySQL 8.0.40 Testcontainers, JUnit 5, AssertJ, Mockito, Gradle 9.6.1 Wrapper.

## Global Constraints

- General `PATCH /api/v1/store-operator/stores/{storeId}` permits only `OPEN` and `TEMPORARILY_CLOSED`; OPER-009 close-store behavior is excluded.
- Business registration numbers remain unavailable after `CLOSED`.
- Public `storeId` values are positive and invalid IDs fail before Service invocation.
- Store onboarding requires `applicantSelfAttested=true` and `requiredTermsAgreed=true`.
- The server, not the client, records `STORE_ONBOARDING_REQUIRED_TERMS_V1` and the acceptance time.
- Stable ownership checks precede idempotency; mutable schedule publication checks run only for new execution inside the callback.
- Existing Flyway migrations V1-V9 are immutable; onboarding evidence uses V10.
- GitHub replies and review-thread resolution are not part of implementation.

---

## File Structure

### Store core behavior

- `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreUpdateRequest.java`
  rejects terminal status in the general update request.
- `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreCreateRequest.java`
  carries the two required onboarding declarations.
- `backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java`
  stores immutable onboarding evidence and protects general-update status transitions.
- `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
  owns the current terms version, acceptance clock, and purpose-specific guards.
- `backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandFingerprint.java`
  includes both declarations in registration identity.
- `backend/src/main/java/com/miriyum/domain/store/core/controller/StoreController.java`
  validates positive Store IDs.
- `backend/src/main/resources/db/migration/V10__add_store_onboarding_evidence.sql`
  upgrades existing Store rows and enforces evidence columns.

### Store schedule behavior

- `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleService.java`
  orders ownership, idempotency replay, and mutable publication authority.
- `backend/src/main/java/com/miriyum/domain/store/schedule/controller/StoreScheduleController.java`
  validates positive Store IDs.
- `backend/src/main/java/com/miriyum/domain/store/schedule/entity/OperatingScheduleVersion.java`
- `backend/src/main/java/com/miriyum/domain/store/schedule/entity/ReservationScheduleVersion.java`
- `backend/src/main/java/com/miriyum/domain/store/schedule/entity/StoreScheduleState.java`
  use private meaningful constructors behind static factories.

### Canonical contracts

- `docs/specs/store-search/spec.md`
- `docs/specs/store-search/openapi.yaml`
- `docs/service-policies/02-store-onboarding.md`
- `docs/specs/mvp1-common/domain-model.md`
- `docs/superpowers/specs/2026-07-31-store-schedule-design.md`

---

### Task 1: Remove `CLOSED` From General Store Update

**Files:**

- Modify: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreUpdateRequest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/dto/StoreUpdateRequestTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/entity/StoreTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java`
- Modify: `docs/specs/store-search/openapi.yaml`

**Interfaces:**

- Consumes: existing `StoreUpdateRequest.operationStatus()`
- Produces: general-update status contract `OPEN | TEMPORARILY_CLOSED`

- [ ] **Step 1: Write failing DTO, aggregate, and MockMvc tests**

Add a DTO validation test whose production mutation is “remove the non-terminal
status constraint”:

```java
@Test
@DisplayName("일반 매장 수정 요청은 CLOSED를 허용하지 않는다")
void closedOperationStatusIsInvalidForGeneralUpdate() {
    StoreUpdateRequest request = new StoreUpdateRequest(
            null, null, null, null, null, null, null, OperationStatus.CLOSED);

    assertThat(validator.validate(request))
            .extracting(ConstraintViolation::getPropertyPath)
            .extracting(Object::toString)
            .contains("$");
}
```

Add an aggregate test that proves no earlier field is partially changed:

```java
@Test
@DisplayName("일반 수정으로 폐점을 요청하면 다른 필드도 변경하지 않는다")
void generalUpdateCannotCloseOrPartiallyMutateStore() {
    Store store = store();

    assertThatThrownBy(() -> store.update(
            "바뀐 이름", null, null, null, null, null,
            null, null, null, OperationStatus.CLOSED))
            .isInstanceOf(ServiceException.class)
            .extracting(errorCode())
            .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);
    assertThat(store.getName()).isEqualTo("미리윰");
    assertThat(store.getOperationStatus()).isEqualTo(OperationStatus.OPEN);
}
```

Add a controller test using authenticated PATCH with a valid idempotency key:

```java
@Test
void closedStatusInGeneralPatchReturnsCommon001WithoutServiceCall() throws Exception {
    authenticateStoreOperator(11L);

    mockMvc.perform(patch("/api/v1/store-operator/stores/7")
                    .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                    .header("Idempotency-Key", TEST_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("""{"operationStatus":"CLOSED"}"""))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_001"));

    then(storeService).shouldHaveNoInteractions();
}
```

- [ ] **Step 2: Run focused tests and verify RED**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.dto.StoreUpdateRequestTest" `
  --tests "com.miriyum.domain.store.core.entity.StoreTest" `
  --tests "com.miriyum.domain.store.core.controller.StoreControllerTest"
```

Expected: the three new tests fail because `CLOSED` is currently accepted.

- [ ] **Step 3: Add the minimal request and aggregate guards**

In `StoreUpdateRequest` add:

```java
@AssertTrue(message = "일반 수정에서는 폐점할 수 없습니다.")
public boolean isNonTerminalOperationStatus() {
    return operationStatus != OperationStatus.CLOSED;
}
```

Replace the aggregate transition check with:

```java
private void requireGeneralUpdateStatus(OperationStatus requestedStatus) {
    if (requestedStatus == OperationStatus.CLOSED) {
        throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
    }
    if (requestedStatus != null && operationStatus == OperationStatus.CLOSED) {
        throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
    }
}
```

Call it before any mutation. Preserve `OPEN <-> TEMPORARILY_CLOSED` and same-state
updates.

In OpenAPI add:

```yaml
    EditableOperationStatus:
      type: string
      enum: [OPEN, TEMPORARILY_CLOSED]
```

Point `StoreUpdateRequest.operationStatus` to `EditableOperationStatus`, while response
schemas continue to use the full `OperationStatus`.

- [ ] **Step 4: Run focused tests and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit Task 1**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/core `
  backend/src/test/java/com/miriyum/domain/store/core `
  docs/specs/store-search/openapi.yaml
git commit -m "fix(store): exclude closure from general update"
```

---

### Task 2: Persist Onboarding Attestation and Required-Terms Evidence

**Files:**

- Create: `backend/src/main/resources/db/migration/V10__add_store_onboarding_evidence.sql`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/dto/StoreCreateRequest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/entity/Store.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreCommandFingerprint.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/dto/ManagedStoreResponseTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/entity/StoreTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreCommandFingerprintTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/repository/StoreRepositoryIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/store/core/repository/StoreOnboardingEvidenceMigrationTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/repository/StoreScheduleRepositoryIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreSchedulePublicationIT.java`
- Modify: `docs/specs/store-search/openapi.yaml`

**Interfaces:**

- Consumes: global `Clock` bean from `JpaAuditingConfig`
- Produces:
  `Store.create(..., LocalDateTime onboardingAcceptedAt, String requiredTermsVersion)`
- Produces persisted getters:
  `getApplicantSelfAttestedAt()`, `getRequiredTermsAgreedAt()`,
  `getRequiredTermsVersion()`

- [ ] **Step 1: Write failing request, fingerprint, Service, and MVC tests**

Add two `StoreCreateRequest` fields:

```java
@AssertTrue boolean applicantSelfAttested,
@AssertTrue boolean requiredTermsAgreed
```

Before production compilation is repaired, update test fixtures to the wished-for
constructor and add validation cases for missing/false declarations.

Add a fingerprint test whose production mutation is “omit either declaration from
the canonical input”:

```java
@Test
void onboardingDeclarationsAffectCreateFingerprint() {
    StoreCreateRequest attested = request(true, true);
    StoreCreateRequest missingAgreement = request(true, false);

    assertThat(StoreCommandFingerprint.forCreate(attested))
            .isNotEqualTo(StoreCommandFingerprint.forCreate(missingAgreement));
}
```

Construct `StoreService` with:

```java
Clock.fixed(Instant.parse("2026-07-31T03:00:00Z"), ZoneOffset.UTC)
```

Capture the saved Store and assert:

```java
assertThat(saved.getApplicantSelfAttestedAt())
        .isEqualTo(LocalDateTime.of(2026, 7, 31, 12, 0));
assertThat(saved.getRequiredTermsAgreedAt())
        .isEqualTo(LocalDateTime.of(2026, 7, 31, 12, 0));
assertThat(saved.getRequiredTermsVersion())
        .isEqualTo("STORE_ONBOARDING_REQUIRED_TERMS_V1");
```

Add MockMvc parameterized cases for each missing/false declaration. Each expects
`COMMON_001` and no Store Service interaction.

- [ ] **Step 2: Run focused non-DB tests and verify RED**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.service.StoreCommandFingerprintTest" `
  --tests "com.miriyum.domain.store.core.service.StoreServiceTest" `
  --tests "com.miriyum.domain.store.core.controller.StoreControllerTest"
```

Expected: compilation or assertions fail because the request and Store evidence
contract does not exist.

- [ ] **Step 3: Implement request validation and canonical fingerprinting**

Append the two primitive boolean fields to `StoreCreateRequest`. In
`StoreCommandFingerprint.forCreate`, append literals in stable order:

```java
.append("|applicantSelfAttested=").append(request.applicantSelfAttested())
.append("|requiredTermsAgreed=").append(request.requiredTermsAgreed())
```

Update every JSON create fixture with:

```json
"applicantSelfAttested": true,
"requiredTermsAgreed": true
```

- [ ] **Step 4: Implement Store evidence and Clock-owned creation**

Add immutable fields:

```java
@Column(name = "applicant_self_attested_at", nullable = false)
private LocalDateTime applicantSelfAttestedAt;

@Column(name = "required_terms_agreed_at", nullable = false)
private LocalDateTime requiredTermsAgreedAt;

@Column(name = "required_terms_version", nullable = false, length = 50)
private String requiredTermsVersion;
```

Extend the private constructor and static factory with:

```java
LocalDateTime onboardingAcceptedAt,
String requiredTermsVersion
```

Set both timestamps from the same non-null argument and reject a null or blank version
with `IllegalArgumentException`.

Add to `StoreService`:

```java
private static final String REQUIRED_TERMS_VERSION =
        "STORE_ONBOARDING_REQUIRED_TERMS_V1";
private final Clock clock;
```

Inside the new idempotent registration callback calculate once:

```java
LocalDateTime acceptedAt = LocalDateTime.ofInstant(
        clock.instant(),
        ZoneId.of("Asia/Seoul"));
```

Pass `acceptedAt` and `REQUIRED_TERMS_VERSION` to `Store.create`.

Update every direct Store factory call in tests with a fixed literal:

```java
LocalDateTime.of(2026, 7, 31, 12, 0),
"STORE_ONBOARDING_REQUIRED_TERMS_V1"
```

- [ ] **Step 5: Add V10 migration**

Create exactly:

```sql
ALTER TABLE stores
    ADD COLUMN applicant_self_attested_at DATETIME(6) NULL,
    ADD COLUMN required_terms_agreed_at DATETIME(6) NULL,
    ADD COLUMN required_terms_version VARCHAR(50) NULL;

UPDATE stores
SET applicant_self_attested_at = created_at,
    required_terms_agreed_at = created_at,
    required_terms_version = 'STORE_ONBOARDING_REQUIRED_TERMS_V1'
WHERE applicant_self_attested_at IS NULL
   OR required_terms_agreed_at IS NULL
   OR required_terms_version IS NULL;

ALTER TABLE stores
    MODIFY applicant_self_attested_at DATETIME(6) NOT NULL,
    MODIFY required_terms_agreed_at DATETIME(6) NOT NULL,
    MODIFY required_terms_version VARCHAR(50) NOT NULL,
    ADD CONSTRAINT ck_stores_required_terms_version
        CHECK (CHAR_LENGTH(TRIM(required_terms_version)) > 0);
```

- [ ] **Step 6: Add MySQL evidence assertions and verify GREEN**

In `StoreRepositoryIT.storesStoreAndTagsWithFlywaySchema`, assert the three evidence
getters after clearing the persistence context.

Add direct SQL assertions:

```java
Map<String, Object> evidence = jdbcTemplate.queryForMap("""
        SELECT applicant_self_attested_at,
               required_terms_agreed_at,
               required_terms_version
        FROM stores
        WHERE store_id = ?
        """, saved.getId());
assertThat(evidence.get("required_terms_version"))
        .isEqualTo("STORE_ONBOARDING_REQUIRED_TERMS_V1");
```

Create `StoreOnboardingEvidenceMigrationTest` with one MySQL 8.0.40 container.
Migrate only through V9, insert one operator and one Store with
`created_at='2026-07-31 12:00:00.000000'`, then migrate through V10:

```java
Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .target(MigrationVersion.fromVersion("9"))
        .load()
        .migrate();

try (Connection connection = DriverManager.getConnection(
        MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())) {
    connection.createStatement().executeUpdate("""
            INSERT INTO store_operator_accounts (
                email, password_hash, display_name
            ) VALUES ('legacy@example.com', 'hashed', '기존 운영자')
            """);
    connection.createStatement().executeUpdate("""
            INSERT INTO stores (
                store_operator_account_id, business_registration_number,
                business_type, name, description, region, address,
                store_category_code, verification_status, operation_status,
                pickup_eligibility, reservation_enabled, menu_hold_enabled,
                pickup_enabled, created_at, updated_at
            ) VALUES (
                1, '1234567890', 'CAFE', '기존 매장', '', 'SEOUL',
                '서울시 중구', 'CAFE_BAKERY', 'APPROVED', 'OPEN',
                'ELIGIBLE', TRUE, TRUE, TRUE,
                '2026-07-31 12:00:00.000000',
                '2026-07-31 12:00:00.000000'
            )
            """);
}

Flyway.configure()
        .dataSource(MYSQL.getJdbcUrl(), MYSQL.getUsername(), MYSQL.getPassword())
        .load()
        .migrate();
```

Query the migrated row and assert both evidence timestamps equal the legacy
`created_at`, the version equals `STORE_ONBOARDING_REQUIRED_TERMS_V1`, and
`information_schema.columns.IS_NULLABLE='NO'` for all three columns.

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.*" `
  --tests "com.miriyum.domain.store.schedule.repository.StoreScheduleRepositoryIT" `
  --tests "com.miriyum.domain.store.schedule.service.StoreSchedulePublicationIT"
```

Expected: PASS with Flyway V10 and JPA validation.

- [ ] **Step 7: Update the OpenAPI registration contract**

Add both names to `StoreCreateRequest.required` and both boolean properties with
`const: true`. State that the server records the acceptance time and
`STORE_ONBOARDING_REQUIRED_TERMS_V1`; clients do not submit either value.

- [ ] **Step 8: Commit Task 2**

```powershell
git add backend/src docs/specs/store-search/openapi.yaml
git commit -m "feat(store): persist onboarding consent evidence"
```

---

### Task 3: Reject Non-Positive Store IDs at Every Implemented HTTP Boundary

**Files:**

- Modify: `backend/src/main/java/com/miriyum/domain/store/core/controller/StoreController.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/controller/StoreScheduleController.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/controller/StoreControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/controller/StoreScheduleControllerTest.java`

**Interfaces:**

- Consumes: `GlobalExceptionHandler.handleHandlerMethodValidation`
- Produces: `storeId <= 0 -> COMMON_001` without Service invocation

- [ ] **Step 1: Write failing parameterized MockMvc tests**

For Store GET and PATCH, and for both schedule PUT endpoints, use `@ValueSource(longs =
{0L, -1L})`. Example:

```java
@ParameterizedTest
@ValueSource(longs = {0L, -1L})
void nonPositiveStoreIdRejectsOperatingPublicationBeforeService(long storeId)
        throws Exception {
    authenticateStoreOperator(11L);

    mockMvc.perform(put("/api/v1/store-operator/stores/{storeId}/operating-hours",
                    storeId)
                    .header(HttpHeaders.AUTHORIZATION, "Bearer store-token")
                    .header("Idempotency-Key", TEST_KEY)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(validOperatingJson()))
            .andExpect(status().isBadRequest())
            .andExpect(jsonPath("$.code").value("COMMON_001"))
            .andExpect(jsonPath("$.details[0].field").value("storeId"));

    then(storeScheduleService).shouldHaveNoInteractions();
}
```

Repeat for reservation publication and core GET/PATCH. Clear Mockito invocations
between parameterized runs when needed.

- [ ] **Step 2: Run both controller suites and verify RED**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.controller.StoreControllerTest" `
  --tests "com.miriyum.domain.store.schedule.controller.StoreScheduleControllerTest"
```

Expected: current controllers reach the mocked Service or return a non-validation
response.

- [ ] **Step 3: Add `@Positive` to each Store path variable**

Import `jakarta.validation.constraints.Positive` and change all four endpoint
parameters to:

```java
@PathVariable @Positive long storeId
```

Do not add `@Validated`; Spring MVC 4.1 method validation must continue through
`HandlerMethodValidationException` and the existing global handler.

- [ ] **Step 4: Run both controller suites and verify GREEN**

Run the Step 2 command. Expected: PASS.

- [ ] **Step 5: Commit Task 3**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/*/controller `
  backend/src/test/java/com/miriyum/domain/store/*/controller
git commit -m "fix(store): validate positive store path IDs"
```

---

### Task 4: Centralize Schedule Publication Authority and Preserve Replay

**Files:**

- Modify: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreService.java`
- Delete: `backend/src/main/java/com/miriyum/domain/store/core/service/StoreManagementView.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/service/StoreScheduleService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/core/service/StoreServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleServiceTest.java`
- Modify: `docs/superpowers/specs/2026-07-31-store-schedule-design.md`

**Interfaces:**

- Produces:
  `void StoreService.requireManagementOwnership(long operatorAccountId, long storeId)`
- Produces:
  `void StoreService.requireSchedulePublicationAuthority(long operatorAccountId, long storeId)`
- Removes callers interpreting `StoreManagementView`

- [ ] **Step 1: Write failing Store guard tests**

Add focused tests:

```java
@Test
void managementOwnershipChecksOnlyExistenceAndOwner() {
    given(storeRepository.findOperatorAccountIdById(STORE_ID))
            .willReturn(Optional.of(OPERATOR_ID));

    storeService.requireManagementOwnership(OPERATOR_ID, STORE_ID);

    then(storeRepository).should().findOperatorAccountIdById(STORE_ID);
}

@Test
void schedulePublicationAuthorityRejectsClosedStore() {
    given(storeRepository.findById(STORE_ID))
            .willReturn(Optional.of(store(OperationStatus.CLOSED)));

    assertThatThrownBy(() ->
            storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
            .isInstanceOf(ServiceException.class)
            .extracting(errorCode())
            .isEqualTo(StoreErrorCode.STORE_STATE_CONFLICT);
}
```

Add the parallel non-`APPROVED` assertion for `STORE_007`.

- [ ] **Step 2: Write failing schedule replay-order tests**

Replace `closedStoreReturnsStore005BeforeIdempotencyExecution` with two tests.

Replay case:

```java
@Test
void successfulReplayDoesNotRecheckMutablePublicationState() {
    given(idempotencyExecutor.execute(any(), any()))
            .willReturn(new IdempotentOutcome(
                    true, 200, "SUCCESS", "STORE_SCHEDULE", "7:OPERATING:1",
                    objectMapper.valueToTree(
                            new OperatingHoursResponse(1, List.of()))));

    ScheduleCommandResult<OperatingHoursResponse> result =
            scheduleService.replaceOperatingHours(
                    OPERATOR_ID, STORE_ID, IdempotencyKey.parse(KEY),
                    operatingRequest());

    assertThat(result.data().version()).isEqualTo(1);
    then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
    then(storeService).should(never())
            .requireSchedulePublicationAuthority(anyLong(), anyLong());
}
```

New execution case:

```java
@Test
void newPublicationChecksMutableAuthorityInsideIdempotentWork() {
    willAnswer(invocation -> {
        Supplier<BusinessResult<?>> work = invocation.getArgument(1);
        return outcome(work.get());
    }).given(idempotencyExecutor).execute(any(), any());
    willThrow(new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT))
            .given(storeService)
            .requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID);

    assertThatThrownBy(() -> scheduleService.replaceOperatingHours(
            OPERATOR_ID, STORE_ID, IdempotencyKey.parse(KEY), operatingRequest()))
            .isInstanceOf(ServiceException.class);
}
```

- [ ] **Step 3: Run Service tests and verify RED**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.core.service.StoreServiceTest" `
  --tests "com.miriyum.domain.store.schedule.service.StoreScheduleServiceTest"
```

Expected: compilation fails for the new guard methods, or replay-order verification
fails against the existing pre-execution check.

- [ ] **Step 4: Implement purpose-specific Store guards**

Add:

```java
@Transactional(readOnly = true)
public void requireManagementOwnership(long operatorAccountId, long storeId) {
    operatorAccountService.getMe(operatorAccountId);
    requireStoreOwnership(operatorAccountId, storeId);
}

@Transactional(readOnly = true)
public void requireSchedulePublicationAuthority(
        long operatorAccountId,
        long storeId
) {
    operatorAccountService.getMe(operatorAccountId);
    Store store = loadManagedStore(operatorAccountId, storeId);
    if (store.getVerificationStatus() != VerificationStatus.APPROVED) {
        throw new ServiceException(StoreErrorCode.VERIFICATION_STATE_CONFLICT);
    }
    if (store.getOperationStatus() == OperationStatus.CLOSED) {
        throw new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT);
    }
}
```

Remove `requireManagementAuthority` and `StoreManagementView` after all callers are
migrated.

- [ ] **Step 5: Reorder Store schedule execution**

Both schedule methods must follow this shape:

```java
storeService.requireManagementOwnership(operatorId, storeId);
IdempotencyCommand command = command(...);
IdempotentOutcome outcome = idempotencyExecutor.execute(command, () -> {
    storeService.requireSchedulePublicationAuthority(operatorId, storeId);
    StoreScheduleState state = initializeAndLock(storeId);
    // existing validation, persistence, activation, response
});
```

Delete `StoreScheduleService.requirePublicationAuthority` and its direct enum
interpretation.

- [ ] **Step 6: Update schedule design and verify GREEN**

Update the authority and idempotency sections so stable ownership is outside and
mutable status checks are inside the callback.

Run the Step 3 command. Expected: PASS.

- [ ] **Step 7: Commit Task 4**

```powershell
git add backend/src/main/java/com/miriyum/domain/store `
  backend/src/test/java/com/miriyum/domain/store `
  docs/superpowers/specs/2026-07-31-store-schedule-design.md
git commit -m "fix(store): centralize publication authority"
```

---

### Task 5: Align Schedule Entity Construction

**Files:**

- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/OperatingScheduleVersion.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/ReservationScheduleVersion.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/schedule/entity/StoreScheduleState.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/dto/StoreScheduleResponseTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/repository/StoreScheduleRepositoryIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreSchedulePublicationIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/schedule/service/StoreScheduleServiceTest.java`

**Interfaces:**

- Keeps: existing public static factory signatures and persistence behavior
- Produces: private required-input constructors; no factory use of protected no-args

- [ ] **Step 1: Characterize all three factory behaviors**

Add or retain behavior assertions for:

```java
OperatingScheduleVersion operating =
        OperatingScheduleVersion.create(7L, 2L, operatingIntervals);
assertThat(operating.getStoreId()).isEqualTo(7L);
assertThat(operating.getVersionNumber()).isEqualTo(2L);
assertThat(operating.getEntries()).hasSize(operatingIntervals.size());

ReservationScheduleVersion reservation =
        ReservationScheduleVersion.create(7L, 3L, 21L, reservationIntervals);
assertThat(reservation.getValidatedOperatingVersionId()).isEqualTo(21L);

StoreScheduleState state = StoreScheduleState.initialize(7L);
assertThat(state.allocateOperatingVersion()).isEqualTo(1L);
assertThat(state.allocateReservationVersion()).isEqualTo(1L);
```

These tests catch missing constructor assignments and changed factory defaults. Do not
assert reflection-level constructor visibility.

- [ ] **Step 2: Run schedule entity consumers as a clean baseline**

Run:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.schedule.dto.StoreScheduleResponseTest" `
  --tests "com.miriyum.domain.store.schedule.repository.StoreScheduleRepositoryIT" `
  --tests "com.miriyum.domain.store.schedule.service.StoreScheduleServiceTest"
```

Expected: PASS before refactor. This task is behavior-preserving, so the baseline is
the characterization gate rather than a missing-feature RED.

- [ ] **Step 3: Add private constructors and simplify factories**

For operating versions:

```java
private OperatingScheduleVersion(
        long storeId,
        long versionNumber,
        LocalDateTime publishedAt,
        List<OperatingScheduleEntry> entries
) {
    this.storeId = storeId;
    this.versionNumber = versionNumber;
    this.publishedAt = publishedAt;
    this.entries = new ArrayList<>(entries);
}
```

The factory maps intervals first and returns `new OperatingScheduleVersion(...)`.
Apply the same pattern to reservation versions, including
`validatedOperatingVersionId`.

For state:

```java
private StoreScheduleState(
        long storeId,
        long nextOperatingVersion,
        long nextReservationVersion
) {
    this.storeId = storeId;
    this.nextOperatingVersion = nextOperatingVersion;
    this.nextReservationVersion = nextReservationVersion;
}

public static StoreScheduleState initialize(long storeId) {
    return new StoreScheduleState(storeId, 1L, 1L);
}
```

Update tests that reflectively call the protected no-args constructor to use
`StoreScheduleState.initialize(STORE_ID)`.

- [ ] **Step 4: Run the focused and integration tests**

Run the Step 2 command plus:

```powershell
.\gradlew.bat test `
  --tests "com.miriyum.domain.store.schedule.service.StoreSchedulePublicationIT"
```

Expected: PASS.

- [ ] **Step 5: Commit Task 5**

```powershell
git add backend/src/main/java/com/miriyum/domain/store/schedule/entity `
  backend/src/test/java/com/miriyum/domain/store/schedule
git commit -m "refactor(store): align schedule entity construction"
```

---

### Task 6: Align Canonical Documents With Permanent Uniqueness and Evidence

**Files:**

- Modify: `docs/specs/store-search/spec.md`
- Modify: `docs/specs/store-search/openapi.yaml`
- Modify: `docs/service-policies/02-store-onboarding.md`
- Modify: `docs/specs/mvp1-common/domain-model.md`

**Interfaces:**

- Produces one canonical rule:
  a business registration number is permanently unavailable to general registration
  after any Store row has claimed it.
- Produces one onboarding rule:
  attestation and required-terms evidence are validated and stored by Store onboarding.

- [ ] **Step 1: Replace every active-only uniqueness statement**

Run:

```powershell
rg -n "활성.*사업자|사업자.*활성|활성 유일|활성 귀속" `
  docs/specs/store-search/spec.md `
  docs/specs/store-search/openapi.yaml `
  docs/service-policies/02-store-onboarding.md `
  docs/specs/mvp1-common/domain-model.md
```

For each match describing Store business-number availability, replace it with the
permanent rule. Keep unrelated uses of “active” such as active reservations unchanged.

Use this exact contract sentence in the Store search spec:

```text
1차 MVP는 사업자등록번호 형식과 중앙 영구 중복을 검증한다. 한 번 매장에
귀속된 번호는 폐점 후에도 일반 등록에서 재사용할 수 없으며, 재개·이전·
복구는 향후 플랫폼 운영자 전용 절차로 분리한다.
```

Change `STORE_002` meaning to “사업자등록번호가 이미 매장에 귀속됨”.

- [ ] **Step 2: Document onboarding evidence and migration limitation**

State in all relevant registration-contract sections:

```text
신규 신청은 신청자 자기확약과 필수 약관 동의가 모두 true여야 한다.
서버는 동의 시각과 STORE_ONBOARDING_REQUIRED_TERMS_V1을 저장한다.
V10 이전 행의 created_at backfill은 기술적 schema 이행 표시이며 새로운
법적 재동의로 해석하지 않는다.
```

Do not claim external identity proof, government validation, or platform approval.

- [ ] **Step 3: Verify document consistency**

Run:

```powershell
rg -n "활성.*사업자|사업자.*활성|활성 유일|활성 귀속" `
  docs/specs/store-search/spec.md `
  docs/specs/store-search/openapi.yaml `
  docs/service-policies/02-store-onboarding.md `
  docs/specs/mvp1-common/domain-model.md
git diff --check
```

Expected: no remaining active-only business-number claims and no whitespace errors.

- [ ] **Step 4: Commit Task 6**

```powershell
git add docs/specs/store-search `
  docs/service-policies/02-store-onboarding.md `
  docs/specs/mvp1-common/domain-model.md
git commit -m "docs: align permanent store ownership contracts"
```

---

### Task 7: Full Verification and Review Traceability

**Files:**

- Modify only if test evidence reveals a defect in an already-listed Task file.
- Do not write GitHub comments or resolve threads.

**Interfaces:**

- Consumes: Tasks 1-6
- Produces: fresh test, build, migration, and diff evidence for PR #67

- [ ] **Step 1: Run Store core focused verification**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.core.*"
```

Record test count, failures, errors, and skipped tests from XML or console output.
Expected: zero failures and zero errors.

- [ ] **Step 2: Run Store schedule focused verification**

Run:

```powershell
.\gradlew.bat test --tests "com.miriyum.domain.store.schedule.*"
```

Expected: zero failures and zero errors, including MySQL concurrency and rollback.

- [ ] **Step 3: Run complete backend build**

Run:

```powershell
.\gradlew.bat clean build
```

Expected: `BUILD SUCCESSFUL`.

- [ ] **Step 4: Verify migration and Hibernate schema**

Confirm test output shows Flyway V10 applied and the Spring context with
`ddl-auto=validate` started successfully. Query the Testcontainers-backed integration
test evidence for:

```text
applicant_self_attested_at NOT NULL
required_terms_agreed_at NOT NULL
required_terms_version NOT NULL
ck_stores_required_terms_version
uk_stores_active_business_number still protects business_registration_number
```

- [ ] **Step 5: Check diff hygiene and requirement coverage**

Run:

```powershell
git diff --check origin/dev...HEAD
git diff --name-status origin/dev...HEAD
git log --oneline origin/dev..HEAD
```

Map the final diff to all seven review items:

```text
1. General PATCH cannot close
2. Permanent business-number documentation
3. Positive Store IDs
4. Onboarding attestation and terms evidence
5. Purpose-specific central authority guards
6. Idempotent replay before mutable checks
7. Private schedule entity constructors
```

- [ ] **Step 6: Inspect unresolved PR threads without mutating GitHub**

Run the configured `fetch_comments.py` workflow from this worktree with
`PYTHONUTF8=1`. Confirm the two original thread IDs remain readable and identify the
commits/tests that address each. Do not reply or resolve.

- [ ] **Step 7: Commit any verification-only test correction**

Only when Step 1-5 required a test-only correction:

```powershell
git add backend/src/test
git commit -m "test(store): complete PR 67 remediation evidence"
```

If no correction was needed, do not create an empty commit.
