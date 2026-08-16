# Issue #279 Review Remediation Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** PR #382의 네 필수 리뷰 결함을 보완해 최신 영향 확인, 2인 승인, 멱등 replay, 기간 만료 경합을 안전하게 보장한다.

**Architecture:** 기존 Store/Reservation/Waiting/Pickup/Payment 공개 port, `HighRiskCommandGuard`, `IdempotencyExecutor`, 불변 감사 원장을 조합한다. 제재 도메인이 트랜잭션과 상태 전이를 소유하고 다른 도메인의 Entity·Repository는 참조하지 않는다.

**Tech Stack:** Java 21, Spring Boot, Spring Data JPA, MySQL, JUnit 5, Mockito, MockMvc, Flyway, OpenAPI/Redocly

## Global Constraints

- Store 단위 제재만 변경하며 같은 대표자의 다른 Store로 전파하지 않는다.
- 기존 확정 거래를 취소·환불·직접 변경하지 않는다.
- 미래 `startsAt`은 거부하고 즉시 시작과 자동 만료만 지원한다.
- 로컬에서는 관련 unit 및 영향 integration class만 실행하며 전체 suite는 GitHub CI로 검증한다.
- `membersupport/**`는 수정하지 않는다.

---

### Task 1: 영향 ID 집합 기반 실행 직전 재검증

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/payment/dto/StorePaymentImpact.java`
- Modify: `backend/src/main/java/com/miriyum/domain/pickup/dto/StorePickupImpact.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/dto/StoreReservationImpact.java`
- Modify: `backend/src/main/java/com/miriyum/domain/reservation/waiting/dto/StoreWaitingImpact.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionFingerprint.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionImpactService.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionImpactServiceTest.java`

**Interfaces:**
- Consumes: 공개 impact DTO의 대상 ID 집합과 count
- Produces: `verify(...)`가 현재 impact를 재조회하고 동일 canonical digest만 허용

- [ ] ID가 같은 건수로 교체되면 `ADMIN_STORE_004`가 발생하는 failing test를 작성한다.
- [ ] `./gradlew test --tests "*StoreSanctionImpactServiceTest"`로 예상 실패를 확인한다.
- [ ] ID 집합 정렬 canonicalization과 실행 직전 재조회를 최소 구현한다.
- [ ] 같은 테스트를 재실행해 통과시킨다.
- [ ] `git diff --check` 후 영향 재검증 변경을 커밋한다.

### Task 2: 전체 기능 제한 고위험 분류 및 미래 시작 거부

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/model/StoreSanctionPolicyCatalog.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/model/StoreSanctionPolicyCatalogTest.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCommandServiceTest.java`

**Interfaces:**
- Consumes: `SanctionShape`
- Produces: `requiresApproval(SanctionShape)`가 전체 `RestrictedFeature` 집합을 고위험으로 분류하고 `validate`가 미래 시작을 거부

- [ ] 전체 기능 제한 승인 필요 및 미래 시작 거부 failing tests를 작성한다.
- [ ] 두 test class를 실행해 예상 실패를 확인한다.
- [ ] 정책 분류와 시간 검증을 최소 구현한다.
- [ ] 두 test class를 재실행해 통과시킨다.
- [ ] 정책 변경을 커밋한다.

### Task 3: 모든 admin-store mutation 멱등 실행

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCaseService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCommandService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/controller/management/PlatformOperatorStoreController.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/AdminStoreIdempotency.java`
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/controller/AdminStoreHttpIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionCommandServiceTest.java`

**Interfaces:**
- Consumes: `IdempotencyCommand(principal, commandType, target, requestFingerprint)`와 mutation callback
- Produces: 모든 mutation의 `IdempotentOutcome`; 동일 key/payload replay, 다른 payload `COMMON_007`

- [ ] create/assign/create-sanction/approve/release replay 및 fingerprint 충돌 failing tests를 작성한다.
- [ ] 관련 service/HTTP tests로 예상 실패를 확인한다.
- [ ] canonical fingerprint factory와 `IdempotencyExecutor.execute` 경계를 구현한다.
- [ ] controller가 저장 status/payload를 재생하도록 응답 변환을 구현한다.
- [ ] 관련 tests를 재실행해 통과시키고 멱등 변경을 커밋한다.

### Task 4: 기간 제재 자동 만료와 수동 해제 경합

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanction.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/entity/StoreSanctionEnums.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/repository/StoreSanctionRepository.java`
- Implement: `backend/src/main/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionExpiryService.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/adminstore/service/StoreSanctionConcurrencyIT.java`

**Interfaces:**
- Consumes: due ACTIVE temporary sanctions ordered by id, per-row pessimistic lock
- Produces: `expireDue(int batchSize)` and `StoreSanction.expire(...)`; manual/automatic race permits one terminal transition. V48에는 이미 `EXPIRED` check와 `(status, ends_at, id)` 인덱스가 있으므로 migration은 추가하지 않는다.

- [ ] expiry and manual-release race failing MySQL test를 작성한다.
- [ ] 해당 integration class만 실행해 예상 실패를 확인한다.
- [ ] `EXPIRED` transition, due query, worker와 기존 `STORE_SANCTION_RELEASED` 감사 동작을 구현한다.
- [ ] 동일 integration class를 재실행해 통과시킨다.
- [ ] 기간 만료 변경을 커밋한다.

### Task 5: 활성 계약·allowlist·최종 검증

**Files:**
- Modify: `docs/specs/admin-store/spec.md`
- Modify: `docs/specs/admin-store/openapi.yaml`
- Modify: `docs/specs/platform-operator-openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`

**Interfaces:**
- Consumes: Tasks 1–4의 HTTP/status/error 계약
- Produces: 실제 test 파일과 일치하는 allowlist 및 통합 OpenAPI

- [ ] OpenAPI/allowlist failing contract test를 먼저 작성하거나 갱신한다.
- [ ] contract test로 예상 실패를 확인한다.
- [ ] spec/OpenAPI를 구현 계약과 일치시킨다.
- [ ] 관련 unit, HTTP, MySQL class와 Redocly lint만 실행한다.
- [ ] `git diff --check`, worktree clean 여부를 확인하고 최종 변경을 커밋한다.
- [ ] feature 브랜치를 push하고 PR 리뷰 thread 각각에 수정·검증 결과를 reply한 뒤 해결 처리한다.
- [ ] GitHub CI 전체 결과와 최신 dev mergeability를 확인한다.
