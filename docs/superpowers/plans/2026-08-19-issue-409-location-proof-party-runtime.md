# Issue #409 GPS Location Proof and Party Runtime Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build GPS-bound one-time waiting registration proofs and transactional party membership, invitation, departure, removal, and representative-transfer Runtime for consumer waiting teams.

**Architecture:** Dedicated persistence models own location proof sessions, invitations, representative-transfer offers, and party audits. Existing `waiting_teams` remains the team/representative/version aggregate, while `waiting_active_memberships` becomes one-account-per-row and many-rows-per-team; every registration or party mutation is serialized through the existing idempotency boundary and a pessimistic team/session lock.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, MySQL 8/Flyway, Jakarta Validation, JUnit 5, Mockito, AssertJ, Testcontainers, OpenAPI 3.0, Redocly CLI 2.35.1.

**Spec:** `docs/superpowers/specs/2026-08-19-issue-409-location-proof-party-runtime-design.md`

## Global Constraints

- Work only in the isolated `codex/409-location-proof-party-runtime` worktree based on `origin/dev` `bb1bed93038369766f4c3a17e08a2af9cbe64813`; never modify the user's original checkout.
- Before the first production edit and again before the final commit, fetch `origin/dev`, verify the highest Flyway version, and keep `V60` only while it remains unclaimed.
- The Issue #409 exact allowlist is authoritative. Add a newly discovered path to #409 with its reason before editing it.
- Location policy is exactly `WAITING_LOCATION_V1`: radius 3,000m, maximum accuracy 100m, maximum age 30s, future tolerance 5s, proof TTL 120s, and pass condition `distance + accuracyMeters <= 3,000m`.
- Raw latitude, longitude, calculated distance, raw accuracy, and measured timestamp exist only in the request call stack; do not persist or return them, include them in audit/log messages, or include them in an idempotency fingerprint.
- Waiting may consume Store coordinates only through `StoreService` and `StoreWaitingLocationProfile`; never import Store Entity or Repository.
- `partySize` never changes in this feature; active membership count must be at most `partySize`.
- Active membership remains globally unique by `consumer_account_id`; a team may have multiple membership rows.
- Party composition and accepted representative changes are allowed only while the team is `WAITING`.
- Do not implement on-site QR/rotating-code Runtime; follow-up #446 owns it.
- Use TDD for every task: add a focused failing test, run and record the expected RED, add minimal production code, then run and record GREEN.
- Locally run only focused unit/contract tests and the affected `WaitingConsumerApiIT` and `WaitingPartyConcurrencyIT`; never run `build`, `check`, the whole `integrationTest`, or every integration shard.
- GitHub CI is the full-suite authority; without exact-head CI, report full verification as `PENDING`.

---

### Task 1: Reconfirm V60 and migrate the persistence invariants

**Files:**
- Create: `backend/src/main/resources/db/migration/V60__create_waiting_location_party_runtime.sql`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingActiveMembership.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingLocationProofSession.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingPartyInvitation.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingRepresentativeTransferOffer.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingPartyAudit.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingLocationProofSessionRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingPartyInvitationRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingRepresentativeTransferOfferRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingPartyAuditRepository.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/repository/WaitingMigrationTest.java`

**Interfaces:**
- Produces: `WaitingLocationProofSession.consume(long accountId, long storeId, Purpose purpose, long waitingTeamId, Instant consumedAt)`.
- Produces: `WaitingPartyInvitation.issue(...)`, `accept(...)`, `revoke(...)`, and nested `Status`.
- Produces: `WaitingRepresentativeTransferOffer.propose(...)`, `accept(...)`, `reject(...)`, `revoke(...)`, and nested `Status`.
- Produces: repositories with `findBy...ForUpdate` methods used by Tasks 4–7.

- [ ] **Step 1: Re-fetch and prove V60 is still free**

Run:

```powershell
git fetch origin dev:refs/remotes/origin/dev
Get-ChildItem backend/src/main/resources/db/migration -Filter 'V*.sql' |
  ForEach-Object { if ($_.Name -match '^V(\d+)__') { [pscustomobject]@{ Version=[int]$Matches[1]; Name=$_.Name } } } |
  Sort-Object Version | Select-Object -Last 10
```

Expected: `origin/dev` is current, the highest version is V59, and no V60 file exists. If V60 is claimed, update #409's allowlist to the next free number before creating a migration.

- [ ] **Step 2: Write migration tests that express every DB invariant**

Add assertions equivalent to:

```java
assertThat(uniqueColumns("waiting_active_memberships"))
        .contains("consumer_account_id")
        .doesNotContain("waiting_team_id");
assertThat(indexColumns("waiting_active_memberships"))
        .contains("waiting_team_id");
assertThat(tableColumns("waiting_location_proof_sessions"))
        .contains("result_category", "accuracy_category", "policy_version",
                "store_coordinate_version", "issued_at", "judged_at", "expires_at", "consumed_at")
        .doesNotContain("latitude", "longitude", "distance_meters", "accuracy_meters", "measured_at");
assertThat(uniqueColumns("waiting_representative_transfer_offers"))
        .contains("active_team_key");
```

Also insert two different accounts into one team, attempt one account in two teams, and assert the former succeeds while the latter violates `uk_waiting_active_memberships_consumer_account`.

- [ ] **Step 3: Run the migration test to verify RED**

Run:

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.waiting.repository.WaitingMigrationTest --console=plain
```

Expected: FAIL because V60 tables/indexes do not exist and V44 still leaves team membership unique.

- [ ] **Step 4: Add the minimal V60 migration**

Use explicit MySQL constraints matching this shape:

```sql
ALTER TABLE waiting_active_memberships
    ADD INDEX idx_waiting_active_memberships_team (waiting_team_id),
    DROP INDEX uk_waiting_active_memberships_team;

CREATE TABLE waiting_location_proof_sessions (
    location_proof_session_id CHAR(36) CHARACTER SET ascii COLLATE ascii_bin NOT NULL,
    consumer_account_id BIGINT NOT NULL,
    store_id BIGINT NOT NULL,
    purpose VARCHAR(32) NOT NULL,
    result_category VARCHAR(32) NOT NULL,
    accuracy_category VARCHAR(32) NOT NULL,
    policy_version VARCHAR(50) NOT NULL,
    store_coordinate_version BIGINT NOT NULL,
    issued_at DATETIME(6) NOT NULL,
    judged_at DATETIME(6) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    consumed_at DATETIME(6) NULL,
    consumed_waiting_team_id BIGINT NULL,
    PRIMARY KEY (location_proof_session_id),
    CONSTRAINT ck_waiting_location_proof_consumption CHECK (
        (consumed_at IS NULL AND consumed_waiting_team_id IS NULL)
        OR (consumed_at IS NOT NULL AND consumed_waiting_team_id IS NOT NULL)
    )
);
```

Create the remaining tables with these exact persisted fields and constraints:

- `waiting_party_invitations`: numeric PK, `waiting_team_id`, `inviter_consumer_account_id`, `token_hash CHAR(64) ascii_bin`, `status` in `ISSUED/ACCEPTED/REVOKED/EXPIRED`, `issued_team_version`, `issued_at`, `expires_at`, nullable `accepted_by_consumer_account_id`, `accepted_at`, `revoked_at`; FK every team/account reference, unique `token_hash`, and checks for positive version, `issued_at < expires_at`, and status/timestamp consistency.
- `waiting_representative_transfer_offers`: numeric PK, `waiting_team_id`, `from_consumer_account_id`, `to_consumer_account_id`, `status` in `PROPOSED/ACCEPTED/REJECTED/REVOKED/EXPIRED`, `proposed_team_version`, `proposed_at`, `expires_at`, nullable `decided_at`, and nullable generated-or-maintained `active_team_key` equal to the team ID only while `PROPOSED`; FK every team/account reference, unique `active_team_key`, and checks for different source/target accounts, positive version, `proposed_at < expires_at`, and status/timestamp consistency.
- `waiting_party_audits`: numeric PK, unique `command_id CHAR(36) ascii_bin`, `waiting_team_id`, `actor_consumer_account_id`, nullable `subject_membership_id`, `event_type` in the invitation/membership/transfer events defined in the design, `team_version`, `occurred_at`; FK team/account/membership references and check positive version. Do not add account names, contact data, invitation codes/hashes, location inputs, or calculated distance.

- [ ] **Step 5: Implement persistence entities and locked repository queries**

Use nested enums so no unlisted enum files are required. Locked repositories must use the same pattern as `WaitingTeamRepository`:

```java
@Lock(LockModeType.PESSIMISTIC_WRITE)
@Query("select proof from WaitingLocationProofSession proof where proof.id = :id")
Optional<WaitingLocationProofSession> findByIdForUpdate(@Param("id") UUID id);
```

The location entity constructor must accept only bounded categories/version/timestamps and must have no raw-location field or method parameter.

- [ ] **Step 6: Run migration tests to verify GREEN**

Run the Task 1 test command again.

Expected: PASS; multiple accounts can share one team, one account cannot occupy two teams, and the proof table has no raw-location columns.

- [ ] **Step 7: Commit Task 1**

```powershell
git add -- backend/src/main/resources/db/migration/V60__create_waiting_location_party_runtime.sql backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingActiveMembership.java backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingLocationProofSession.java backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingPartyInvitation.java backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingRepresentativeTransferOffer.java backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingPartyAudit.java backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingLocationProofSessionRepository.java backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingPartyInvitationRepository.java backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingRepresentativeTransferOfferRepository.java backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingPartyAuditRepository.java backend/src/test/java/com/miriyum/domain/reservation/waiting/repository/WaitingMigrationTest.java
git commit -m "feat(waiting): add location and party persistence"
```

Before staging, use `git diff --name-only` to ensure only Task 1 allowlisted paths are included.

### Task 2: Publish Store coordinates and implement pure GPS judgment

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/store/dto/contract/StoreWaitingLocationProfile.java`
- Modify: `backend/src/main/java/com/miriyum/domain/store/service/StoreService.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingLocationProofContracts.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingLocationPolicy.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingLocationProofService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/store/service/StoreServiceTest.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingLocationProofServiceTest.java`

**Interfaces:**
- Produces: `StoreWaitingLocationProfile(long storeId, BigDecimal latitude, BigDecimal longitude, long coordinateVersion, boolean locationProofEligible)`.
- Produces: `WaitingLocationProofService.issue(long accountId, long storeId, WaitingLocationProofContracts.Request request)` returning `WaitingLocationProofContracts.Snapshot`.
- Consumes: Task 1 `WaitingLocationProofSessionRepository`.

- [ ] **Step 1: Write Store boundary RED tests**

Assert a verified, approved, open Store returns coordinates and `geocodingAddressVersion`, while unverified/missing coordinates return `locationProofEligible=false` without exposing the Store entity:

```java
assertThat(storeService.getWaitingLocationProfile(STORE_ID))
        .isEqualTo(new StoreWaitingLocationProfile(
                STORE_ID, new BigDecimal("37.566826000000000"),
                new BigDecimal("126.978656700000000"), 2L, true));
```

- [ ] **Step 2: Write location judgment RED tests**

Cover exact boundary cases: `distance + accuracy == 3000` passes, `3000.001` fails, accuracy 100 passes, accuracy above 100 is `ACCURACY_INSUFFICIENT`, age 30s passes, age above 30s is stale, future 5s passes, future above 5s is stale, permission denied/unavailable/suspected manipulation never pass, and non-verified Store fails closed.

Assert the saved entity and returned snapshot contain none of the sentinel strings `37.123456789012345`, `127.987654321098765`, `73.25`, or the measured timestamp.

- [ ] **Step 3: Run focused tests to verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.store.service.StoreServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingLocationProofServiceTest --console=plain
```

Expected: FAIL because the DTO, policy, and proof service do not exist.

- [ ] **Step 4: Implement the public Store DTO and service method**

Add only this new public projection method:

```java
@Transactional(readOnly = true)
public StoreWaitingLocationProfile getWaitingLocationProfile(long storeId) {
    Store store = storeRepository.findById(storeId)
            .orElseThrow(() -> new ServiceException(StoreErrorCode.STORE_NOT_FOUND));
    return waitingLocationProfile(store);
}
```

Map `latitude`, `longitude`, and `geocodingAddressVersion` only inside `StoreService`; no Waiting class imports Store Entity/Repository.

- [ ] **Step 5: Implement `WAITING_LOCATION_V1` and judgment**

Use a pure policy function with constants and a numerically stable haversine calculation:

```java
static final String VERSION = "WAITING_LOCATION_V1";
static final BigDecimal RADIUS_METERS = new BigDecimal("3000");
static final BigDecimal MAX_ACCURACY_METERS = new BigDecimal("100");
static final Duration MAX_MEASUREMENT_AGE = Duration.ofSeconds(30);
static final Duration FUTURE_TOLERANCE = Duration.ofSeconds(5);
static final Duration PROOF_TTL = Duration.ofSeconds(120);
```

Create and save only category/version/timestamp data after judgment. Do not log the request object or add a `toString()` that contains raw values.

- [ ] **Step 6: Run focused tests to verify GREEN**

Run the Task 2 command again.

Expected: PASS for all geometry, time, denial, integrity, Store-boundary, and non-persistence assertions.

- [ ] **Step 7: Commit Task 2**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/store/dto/contract/StoreWaitingLocationProfile.java backend/src/main/java/com/miriyum/domain/store/service/StoreService.java backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingLocationProofContracts.java backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingLocationPolicy.java backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingLocationProofService.java backend/src/test/java/com/miriyum/domain/store/service/StoreServiceTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingLocationProofServiceTest.java
git commit -m "feat(waiting): issue minimal GPS proof sessions"
```

### Task 3: Contract and expose the consumer location-proof API

**Files:**
- Modify: `docs/specs/waiting/openapi.yaml`
- Modify: `docs/specs/consumer-openapi.yaml`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/exception/ReservationErrorCode.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/controller/consumer/WaitingConsumerController.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/exception/ReservationErrorCodeTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/controller/WaitingConsumerControllerTest.java`

**Interfaces:**
- Consumes: Task 2 `WaitingLocationProofService.issue(...)`.
- Produces: secured POST `/api/v1/consumers/me/stores/{storeId}/waiting-location-proofs`.
- Produces: `WAITING_013` through `WAITING_017` HTTP 409 error constants for later tasks.

- [ ] **Step 1: Write contract and MockMvc RED tests**

Require one-to-one path presence in Waiting OpenAPI and `consumer-openapi.yaml`; assert security accepts a consumer JWT and rejects store-operator/anonymous credentials. MockMvc must assert the 200 response has `proofSessionId`, category/version/timestamps and does not have `latitude`, `longitude`, `distanceMeters`, `accuracyMeters`, or `measuredAt`.

Add exact error-code tests:

```java
assertThat(ReservationErrorCode.LOCATION_PROOF_INVALID.getCode()).isEqualTo("WAITING_013");
assertThat(ReservationErrorCode.PARTY_INVITATION_INVALID.getCode()).isEqualTo("WAITING_014");
assertThat(ReservationErrorCode.PARTY_MUTATION_NOT_ALLOWED.getCode()).isEqualTo("WAITING_015");
assertThat(ReservationErrorCode.REPRESENTATIVE_TRANSFER_INVALID.getCode()).isEqualTo("WAITING_016");
assertThat(ReservationErrorCode.PARTY_CAPACITY_EXCEEDED.getCode()).isEqualTo("WAITING_017");
```

- [ ] **Step 2: Run contract tests to verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.reservation.exception.ReservationErrorCodeTest --tests com.miriyum.domain.reservation.waiting.WaitingOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest --tests com.miriyum.domain.reservation.waiting.controller.WaitingConsumerControllerTest --console=plain
```

Expected: FAIL for missing error codes/path/controller/security matcher.

- [ ] **Step 3: Add the minimal OpenAPI operation and schemas**

The operation must have `x-location-raw-retention: none`, no idempotency header, the conditional request-field contract, bounded result enums, consumer authentication responses, `STORE_001` for missing Store, and no on-site/QR schema.

- [ ] **Step 4: Add the controller and security path**

Add a method shaped as:

```java
@PostMapping("/stores/{storeId}/waiting-location-proofs")
public ApiResponse<WaitingLocationProofContracts.Snapshot> issueLocationProof(
        @AuthenticationPrincipal AuthenticatedPrincipal principal,
        @PathVariable @Positive long storeId,
        @Valid @RequestBody WaitingLocationProofContracts.Request request) {
    return ApiResponse.success("웨이팅 위치를 판정했습니다.",
            locationProofService.issue(principal.accountId(), storeId, request));
}
```

- [ ] **Step 5: Run contract tests to verify GREEN**

Run the Task 3 command again.

Expected: PASS with exact path/audience/security/privacy shape.

- [ ] **Step 6: Commit Task 3**

```powershell
git add -- docs/specs/waiting/openapi.yaml docs/specs/consumer-openapi.yaml backend/src/main/java/com/miriyum/domain/reservation/config/ReservationSecurityConfig.java backend/src/main/java/com/miriyum/domain/reservation/exception/ReservationErrorCode.java backend/src/main/java/com/miriyum/domain/reservation/waiting/controller/consumer/WaitingConsumerController.java backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java backend/src/test/java/com/miriyum/domain/reservation/exception/ReservationErrorCodeTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingOpenApiContractTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/controller/WaitingConsumerControllerTest.java
git commit -m "feat(waiting): expose GPS proof contract"
```

### Task 4: Atomically consume proof during idempotent registration

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingConsumerCreateRequest.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingConsumerCommandFacade.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingCreationService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingConsumerCommandFacadeTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingCreationServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingConsumerApiIT.java`
- Modify: `docs/specs/waiting/openapi.yaml`

**Interfaces:**
- Consumes: Task 1 `WaitingLocationProofSession.consume(...)` and repository lock.
- Changes: `WaitingConsumerCreateRequest(LocalDate businessDate, int partySize, UUID locationProofSessionId)`.
- Changes: `WaitingCreationService.createForConsumer(..., IdempotencyKey key, UUID locationProofSessionId)`; remove the boolean feature flag.

- [ ] **Step 1: Write unit RED tests for binding and rollback order**

Assert registration rejects non-verified, expired, consumed, other-account, other-store, and other-purpose sessions with `WAITING_013`; assert the fingerprint contains the session ID but none of the raw coordinate sentinels. Verify the session lock runs inside the idempotency callback before Store/reception/sequence writes.

- [ ] **Step 2: Write MySQL RED cases in `WaitingConsumerApiIT`**

Add cases that prove:

```java
assertThat(count("waiting_teams")).isEqualTo(1);
assertThat(count("waiting_active_memberships")).isEqualTo(1);
assertThat(count("waiting_queue_sequences")).isEqualTo(1);
assertThat(consumedTeamId(proofId)).isEqualTo(createdTeamId);
```

Force a post-proof failure by closing reception or creating an account-wide membership and assert `consumed_at` stays null and no team/sequence/audit/event is created.

- [ ] **Step 3: Run Task 4 tests to verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.reservation.waiting.service.WaitingConsumerCommandFacadeTest --tests com.miriyum.domain.reservation.waiting.service.WaitingCreationServiceTest --console=plain
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.waiting.WaitingConsumerApiIT --console=plain
```

Expected: focused unit tests fail for the missing session argument/guard; the single integration class fails because registration still uses the boolean gate.

- [ ] **Step 4: Implement proof consumption inside the existing transaction**

Replace `requireLocationProofConnected` with:

```java
WaitingLocationProofSession proof = proofRepository.findByIdForUpdate(locationProofSessionId)
        .orElseThrow(WaitingCreationService::invalidLocationProof);
proof.requireConsumable(consumerAccountId, storeId,
        WaitingLocationProofSession.Purpose.WAITING_REGISTRATION, clock.instant());
// existing Store/reception/membership/sequence/team writes
proof.consume(consumerAccountId, storeId,
        WaitingLocationProofSession.Purpose.WAITING_REGISTRATION,
        team.getId(), clock.instant());
```

Keep both calls inside `transactionExecutor.execute(() -> idempotencyExecutor.execute(...))`. Remove the `@Value` flag from the facade.

- [ ] **Step 5: Update OpenAPI registration request and run GREEN**

Require `locationProofSessionId` as UUID and remove `x-runtime-default: disabled`. Run both Task 4 commands again.

Expected: PASS; failure rolls proof consumption back and replay returns the original team snapshot without a second consume.

- [ ] **Step 6: Commit Task 4**

```powershell
git add -- docs/specs/waiting/openapi.yaml backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingConsumerCreateRequest.java backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingConsumerCommandFacade.java backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingCreationService.java backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingConsumerCommandFacadeTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingCreationServiceTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingConsumerApiIT.java
git commit -m "feat(waiting): bind GPS proof to registration"
```

### Task 5: Add membership snapshots, invitations, acceptance, departure, and removal

**Files:**
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingPartyContracts.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingConsumerSnapshot.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingTeam.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/repository/WaitingActiveMembershipRepository.java`
- Create: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingPartyService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingConsumerQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/controller/consumer/WaitingConsumerController.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingPartyServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingConsumerQueryServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/controller/WaitingConsumerControllerTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/entity/WaitingTeamTest.java`
- Modify: `docs/specs/waiting/openapi.yaml`
- Modify: `docs/specs/consumer-openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`

**Interfaces:**
- Produces: `WaitingPartyService.issueInvitation`, `revokeInvitation`, `acceptInvitation`, `depart`, `removeMember`.
- Produces: `WaitingTeam.requirePartyMutable(long expectedVersion)` and `WaitingTeam.partyChanged(long expectedVersion)`.
- Changes: consumer snapshots become viewer-aware and include `List<MemberSnapshot>`.

- [ ] **Step 1: Write party service RED tests**

Cover representative-only issue/revoke/remove, member-only self-departure, representative departure rejection, one-time/expired/revoked invitation rejection, invite acceptance without team/sequence creation, global account conflict, capacity, `WAITING`-only mutation, version increment, and audit command uniqueness.

Assert the fresh invitation response has a raw code, the idempotency stored payload omits it, and replay returns the same invitation ID with `invitationCode == null`.

- [ ] **Step 2: Write snapshot/controller/OpenAPI RED tests**

Assert current-team lookup works for any active member, cancellation remains representative-only, and snapshots expose only:

```json
{"membershipId":"123","role":"MEMBER","joinedAt":"2026-08-19T00:00:00Z","self":true}
```

Reject `consumerAccountId`, name, email, phone, invitation token hash, and location fields in schemas and JSON.

- [ ] **Step 3: Run Task 5 tests to verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.reservation.waiting.service.WaitingPartyServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingConsumerQueryServiceTest --tests com.miriyum.domain.reservation.waiting.controller.WaitingConsumerControllerTest --tests com.miriyum.domain.reservation.waiting.entity.WaitingTeamTest --tests com.miriyum.domain.reservation.waiting.WaitingOpenApiContractTest --tests com.miriyum.architecture.AudienceOpenApiContractTest --console=plain
```

Expected: FAIL for absent services/routes/member snapshots and the old representative-only query filter.

- [ ] **Step 4: Implement minimal invitation and membership mutations**

All commands must claim idempotency, resolve team ID, lock team, lock invitation when applicable, and then inspect memberships. Use repository methods shaped as:

```java
List<WaitingActiveMembership> findAllByWaitingTeamIdOrderById(long waitingTeamId);
long countByWaitingTeamId(long waitingTeamId);
Optional<WaitingActiveMembership> findByIdAndWaitingTeamId(long id, long waitingTeamId);
```

Hash invitation codes with SHA-256 before persistence. Hold the fresh raw token only in a local command holder; never place it in `BusinessResult.data()` stored by `IdempotencyExecutor`.

- [ ] **Step 5: Add routes/contracts and run GREEN**

Add invitation issue/revocation/acceptance, departure, and removal operations with UUID idempotency headers and exact `WAITING_003/005/006/011/014/015/017` responses. Run the Task 5 command again.

Expected: PASS; no route creates a queue sequence, and member snapshots contain no account/contact data.

- [ ] **Step 6: Commit Task 5**

Stage only the Task 5 allowlisted files and commit:

```powershell
git commit -m "feat(waiting): add party invitation membership runtime"
```

### Task 6: Add proposed-and-accepted representative transfer

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/entity/WaitingTeam.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingPartyService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/WaitingPartyContracts.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/controller/consumer/WaitingConsumerController.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/entity/WaitingTeamTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingPartyServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/controller/WaitingConsumerControllerTest.java`
- Modify: `docs/specs/waiting/openapi.yaml`
- Modify: `docs/specs/consumer-openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingOpenApiContractTest.java`
- Modify: `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`

**Interfaces:**
- Produces: `WaitingPartyService.proposeTransfer`, `acceptTransfer`, `rejectTransfer`, `revokeTransfer`.
- Produces: `WaitingTeam.transferRepresentative(long expectedVersion, long nextRepresentativeAccountId)`.

- [ ] **Step 1: Write representative transfer RED tests**

Prove only the current representative can propose/revoke, only the target member can accept/reject, target must be an active membership in the same team, only one pending offer exists, TTL is five minutes, and reject/expire/revoke preserve the original representative/version.

Assert acceptance changes `consumer_account_id`, increments version exactly once, preserves both memberships, and records one `REPRESENTATIVE_TRANSFER_ACCEPTED` audit.

- [ ] **Step 2: Run Task 6 tests to verify RED**

Run the Task 5 focused command, which already selects every modified unit/contract class.

Expected: FAIL for missing offer methods/routes and missing team transfer behavior.

- [ ] **Step 3: Implement transfer state transitions and API**

Implement the entity method minimally:

```java
public void transferRepresentative(long expectedVersion, long nextAccountId) {
    requireVersion(expectedVersion);
    requireStatus(WaitingTeamStatus.WAITING);
    consumerAccountId = requirePositive(nextAccountId, "nextAccountId");
    version++;
}
```

`WaitingPartyService` must lock team before offer, revalidate target membership under the lock, and clear `activeTeamKey` on every terminal offer decision.

- [ ] **Step 4: Run Task 6 tests to verify GREEN**

Run the Task 5 command again.

Expected: PASS for proposal lifecycle, authorization, TTL, version, privacy, and route contracts.

- [ ] **Step 5: Commit Task 6**

Stage only Task 6 paths and commit:

```powershell
git commit -m "feat(waiting): add accepted representative transfer"
```

### Task 7: Make all terminal flows delete multi-member teams safely

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingReservationConversionService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingReservationConversionServiceTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingConsumerApiIT.java`

**Interfaces:**
- Consumes: Task 5 `WaitingActiveMembershipRepository.deleteByWaitingTeamId` returning the total deleted row count.
- Produces: every terminal transition removes one or more memberships atomically and rejects zero-row corruption.

- [ ] **Step 1: Write multi-member terminal RED tests**

Change mocks from `deleteByWaitingTeamId(...) == 1` assumptions to prove `2` and `3` are valid and `0` is `WAITING_008`. Cover consumer cancel, operator cancel/check-in/close/no-show where membership is released, closure worker, and completed reservation conversion.

- [ ] **Step 2: Run focused tests to verify RED**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.reservation.waiting.service.WaitingLedgerServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingClosureServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingReservationConversionServiceTest --console=plain
```

Expected: FAIL because existing code requires exactly one deleted row.

- [ ] **Step 3: Implement the shared invariant in each existing service**

Replace each exact-one condition with:

```java
if (membershipRepository.deleteByWaitingTeamId(team.getId()) < 1L) {
    throw new ServiceException(ReservationErrorCode.WAITING_ACTIVE_MEMBERSHIP_CONFLICT);
}
```

Do not introduce cross-service refactoring outside the allowlist.

- [ ] **Step 4: Run focused tests and affected API IT to verify GREEN**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.reservation.waiting.service.WaitingLedgerServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingClosureServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingReservationConversionServiceTest --console=plain
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.waiting.WaitingConsumerApiIT --console=plain
```

Expected: PASS; terminal teams leave zero memberships and every former member may later register/join another team.

- [ ] **Step 5: Commit Task 7**

```powershell
git add -- backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerService.java backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureService.java backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingReservationConversionService.java backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingLedgerServiceTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingClosureServiceTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/service/WaitingReservationConversionServiceTest.java backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingConsumerApiIT.java
git commit -m "fix(waiting): release every terminal team membership"
```

### Task 8: Prove MySQL concurrency and raw-location non-retention

**Files:**
- Modify: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingConsumerApiIT.java`
- Create: `backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingPartyConcurrencyIT.java`

**Interfaces:**
- Consumes: all Runtime APIs from Tasks 2–7.
- Produces: executable evidence for registration, invitation, account uniqueness, capacity, transfer/cancel convergence, and privacy.

- [ ] **Step 1: Write concurrent registration and privacy RED scenarios**

Use barriers/latches so requests overlap. Assert same proof/same key replays one team, same proof/different keys yields one team and one `WAITING_013`, and the proof row has one consumed team.

Send unique sentinels for latitude, longitude, accuracy, and measured time; query `information_schema.columns`, all new Waiting tables, `waiting_transition_audits`, `waiting_party_audits`, and `idempotency_commands.result_payload`. Attach a Logback test appender around the HTTP request and assert no sentinel is present in any serialized row/response/log event.

- [ ] **Step 2: Write party concurrency RED scenarios**

Add exactly these cases:

```java
@Test void sameInvitationAcceptedInParallelCreatesOneMembership() { }
@Test void oneAccountAcceptingDifferentTeamsInParallelOccupiesOnlyOneTeam() { }
@Test void twoAccountsCompetingForLastPartySlotProduceOneSuccess() { }
@Test void representativeAcceptanceRacingCancellationConvergesToOneOutcome() { }
```

For transfer/cancel, accept exactly two legal final shapes: active `WAITING` team with the new representative and all memberships, or terminal `CANCELLED` team with zero memberships. Reject any mixed state.

- [ ] **Step 3: Run the two integration classes to verify RED**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.waiting.WaitingConsumerApiIT --tests com.miriyum.domain.reservation.waiting.WaitingPartyConcurrencyIT --console=plain
```

Expected: new concurrency/privacy tests fail before missing synchronization or assertions are satisfied.

- [ ] **Step 4: Make only minimal fixes in previously allowlisted production files**

Use the fixed order `idempotency -> team/session lock -> invitation/offer lock -> membership -> audit`. Map account unique violations to `WAITING_011`, stale versions to `WAITING_005`, and exhausted retryable MySQL 1213/1205 failures to `COMMON_009`. Do not add broad retries for validation, authorization, integrity, or unique constraint failures.

- [ ] **Step 5: Run the two integration classes to verify GREEN**

Run the Task 8 command again.

Expected: PASS with one legal result per race and zero raw-location sentinel occurrences.

- [ ] **Step 6: Commit Task 8**

```powershell
git add -- backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingConsumerApiIT.java backend/src/test/java/com/miriyum/domain/reservation/waiting/WaitingPartyConcurrencyIT.java
git add -- backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingPartyService.java backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingConsumerCommandFacade.java backend/src/main/java/com/miriyum/domain/reservation/waiting/service/WaitingCreationService.java
git commit -m "test(waiting): prove location and party concurrency"
```

Before staging, inspect `git diff --name-only`; omit any unchanged production path from the second command and do not stage any unrelated file.

### Task 9: Final focused verification, migration collision check, and scope audit

**Files:**
- Modify only if a verification failure identifies a defect in an already allowlisted file.

**Interfaces:**
- Produces: final local evidence and a clean allowlist diff ready for push/CI.

- [ ] **Step 1: Re-fetch and recheck migration collision**

Run the Task 1 fetch/version commands again.

Expected: V60 remains free on latest `origin/dev`. If it is claimed, update #409 first, rename the migration, update migration tests/spec/plan references, and then continue.

- [ ] **Step 2: Run every focused unit and contract test once**

```powershell
.\gradlew.bat test --tests com.miriyum.domain.store.service.StoreServiceTest --tests com.miriyum.domain.reservation.exception.ReservationErrorCodeTest --tests com.miriyum.architecture.AudienceOpenApiContractTest --tests com.miriyum.domain.reservation.waiting.WaitingOpenApiContractTest --tests com.miriyum.domain.reservation.waiting.controller.WaitingConsumerControllerTest --tests com.miriyum.domain.reservation.waiting.entity.WaitingTeamTest --tests com.miriyum.domain.reservation.waiting.repository.WaitingMigrationTest --tests com.miriyum.domain.reservation.waiting.service.WaitingLocationProofServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingPartyServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingConsumerCommandFacadeTest --tests com.miriyum.domain.reservation.waiting.service.WaitingConsumerQueryServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingCreationServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingLedgerServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingClosureServiceTest --tests com.miriyum.domain.reservation.waiting.service.WaitingReservationConversionServiceTest --console=plain
```

Expected: PASS. This is a focused selection, not the full backend unit suite.

- [ ] **Step 3: Run only the affected integration classes**

```powershell
.\gradlew.bat integrationTest --tests com.miriyum.domain.reservation.waiting.WaitingConsumerApiIT --tests com.miriyum.domain.reservation.waiting.WaitingPartyConcurrencyIT --console=plain
```

Expected: PASS. Do not run any other integration shard/class unless a focused failure proves it is directly affected.

- [ ] **Step 4: Run OpenAPI lint and bundle**

From the repository's documented OpenAPI tooling directory, use the pinned version:

```powershell
npx --yes @redocly/cli@2.35.1 lint docs/specs/waiting/openapi.yaml docs/specs/consumer-openapi.yaml
npx --yes @redocly/cli@2.35.1 bundle docs/specs/consumer-openapi.yaml --output $env:TEMP\issue-409-consumer-openapi.yaml
```

Expected: both descriptions are valid and bundle exits 0. Delete only the explicit temporary bundle after inspection.

- [ ] **Step 5: Audit exact scope and whitespace**

```powershell
git diff --name-only origin/dev...HEAD
git diff --check origin/dev...HEAD
git status --short --branch
```

Expected: every changed path appears in #409's exact allowlist; `git diff --check` has no output; no temporary file is tracked or untracked.

- [ ] **Step 6: Record verification and commit any verification-only correction**

If no correction is needed, do not create an empty commit. If a focused correction was needed, rerun its RED/GREEN test and commit only its allowlisted files:

```powershell
git commit -m "fix(waiting): close issue 409 verification gaps"
```

The handoff must report unit/contract counts, integration-class results, raw-location non-retention evidence, concurrency outcomes, Redocly lint/bundle, `git diff --check`, migration recheck, and full GitHub CI as `PENDING` until an exact-head run exists.
