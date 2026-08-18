# Pending Sanction Approvals Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** 승인 가능한 다른 운영자의 `PENDING_ADDITIONAL_APPROVAL` 회원 제재를 최소 필드와 현재 version으로 조회하는 플랫폼 운영자 API를 추가한다.

**Architecture:** 기존 #278 `MemberSanction` 원장에 read-only pageable query를 추가하고 전용 query service가 현재 DB authority snapshot의 permission·role을 선검사한 뒤 자기 제안을 제외한다. 기존 member-support Controller와 audience OpenAPI에 GET projection만 연결하며 승인 명령과 상태 전이는 변경하지 않는다.

**Tech Stack:** Java 21, Spring Boot MVC/Security/Data JPA, JUnit 5, Mockito, MockMvc, MySQL 8 Testcontainers, OpenAPI 3.1

**Spec:** `docs/superpowers/specs/2026-08-19-pending-sanction-approvals-design.md`

## Global Constraints

- 기준은 `origin/dev@bb1bed93038369766f4c3a17e08a2af9cbe64813`이다.
- URL은 `GET /api/v1/platform-operators/member-sanctions/pending-additional-approvals`다.
- 조회는 `ACCOUNT_PERMANENT_SANCTION_APPROVE` permission과 `SUPER_ADMIN` role을 모두 요구한다.
- 상태는 `PENDING_ADDITIONAL_APPROVAL`만, 제안자는 현재 운영자와 다른 항목만 반환한다.
- 응답 항목은 `sanctionId`, `version`, `accountType`, `accountId`, `reasonCode`, `policyVersion`, `proposedAt`만 허용한다.
- `version`은 기존 승인 POST의 `If-Match`에 쓰는 member-support case row version이다.
- page는 0 기반, size 기본 20·범위 1~100, 정렬은 `proposedAt DESC, id DESC`다.
- 기존 승인 명령, 제재 정책·상태 전이, 회원 Entity·Repository와 다른 도메인 내부 구현은 변경·참조하지 않는다.
- 로컬에서는 관련 unit class와 `PlatformOperatorMemberSupportHttpIT`, `PlatformOperatorFeatureFlagIT`만 실행한다. `build`, `check`, 전체 `integrationTest`, integration A~D 전체는 실행하지 않는다.

---

### Task 1: 활성 계약과 런타임 드리프트 RED

**Files:**
- Modify: `docs/specs/member-support/spec.md`
- Modify: `docs/specs/member-support/openapi.yaml`
- Modify: `docs/specs/platform-operator-openapi.yaml`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`

**Interfaces:**
- Consumes: 승인된 path, 최소 7필드 schema, 전용 audience 정책
- Produces: operationId `listPendingPermanentMemberSanctionApprovals`와 런타임 mapping 회귀 테스트

- [ ] **Step 1: OpenAPI 계약 테스트에 새 path와 최소 schema 기대값을 추가한다.**

```java
private static final String PENDING_SANCTION_APPROVALS_PATH =
        "/api/v1/platform-operators/member-sanctions/pending-additional-approvals";

assertThat(properties).containsOnlyKeys(
        "sanctionId", "version", "accountType", "accountId",
        "reasonCode", "policyVersion", "proposedAt");
```

Controller의 `@GetMapping`을 reflection으로 읽어 새 path와 method 이름 `pendingAdditionalApprovals`가 계약 operation에 대응하는지도 검증한다. public/consumer/store-operator/MVP entrypoint에는 path가 없음을 함께 단언한다.

- [ ] **Step 2: 계약 테스트를 실행해 RED를 확인한다.**

Run: `cd backend; .\gradlew.bat test --tests com.miriyum.domain.platformoperator.PlatformOperatorOpenApiContractTest`

Expected: FAIL because `PlatformOperatorMemberSupportController`에 새 GET mapping이 없다.

- [ ] **Step 3: 활성 spec/OpenAPI 초안의 placeholder·schema·단일 `$ref`를 자체 검토한다.**

Run: `rg -n "TBD|TODO|contract-only" ../docs/specs/member-support ../docs/specs/platform-operator-openapi.yaml`

Expected: 새 #425 계약에 placeholder나 contract-only 표시가 없다.

### Task 2: 권한·제안자 분리·페이지 projection service

**Files:**
- Create: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/PendingMemberSanctionQueryServiceTest.java`
- Create: `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/PendingMemberSanctionQueryService.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberSanctionRepository.java`
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/dto/membersupport/MemberSupportResponses.java`

**Interfaces:**
- Consumes: `OperatorAuthorityReader.requireCurrentAuthority(long operatorId, long expectedAuthorityVersion)`, `MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL`
- Produces: `PendingMemberSanctionQueryService.list(PlatformOperatorPrincipal principal, int page, int size)` returning `PendingSanctionApprovalPageResponse`

- [ ] **Step 1: permission 또는 SUPER_ADMIN role이 없으면 repository를 호출하지 않는 failing unit tests를 작성한다.**

```java
when(authorities.requireCurrentAuthority(9L, 2L)).thenReturn(
        new OperatorAuthority(9L, 2L, Set.of(),
                Set.of(PlatformOperatorPermission.ACCOUNT_PERMANENT_SANCTION_APPROVE)));

assertThatThrownBy(() -> service.list(PRINCIPAL, 0, 20))
        .isInstanceOfSatisfying(ServiceException.class,
                error -> assertThat(error.getErrorCode())
                        .isEqualTo(AdminAuthorizationErrorCode.AUTHORIZATION_DENIED));
verifyNoInteractions(sanctions);
```

별도 test는 `SUPER_ADMIN`이지만 permission이 없는 snapshot을 사용한다. authority reader 호출 인자는 principal의 operator ID와 authority version으로 검증한다.

- [ ] **Step 2: unit test를 실행해 RED를 확인한다.**

Run: `cd backend; .\gradlew.bat test --tests com.miriyum.domain.platformoperator.membersupport.PendingMemberSanctionQueryServiceTest`

Expected: FAIL to compile because query service and response records do not exist.

- [ ] **Step 3: status·제안자 제외·정렬·version mapping·페이지 경계 failing tests를 추가한다.**

```java
ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
verify(sanctions).findApprovalCandidates(
        eq(MemberSanctionStatus.PENDING_ADDITIONAL_APPROVAL), eq(9L), pageable.capture());
assertThat(pageable.getValue().getSort().toString())
        .isEqualTo("proposedAt: DESC,id: DESC");
assertThat(result.content().getFirst().version())
        .isEqualTo(pending.getSupportCase().getRowVersion());
```

`page=-1`, `size=0`, `size=101`은 `IllegalArgumentException`이고 repository를 호출하지 않아야 한다. 응답 record는 정확히 다음 형태로 정의한다.

```java
public record PendingSanctionApprovalResponse(
        String sanctionId, long version, MemberAccountType accountType,
        String accountId, String reasonCode, String policyVersion,
        OffsetDateTime proposedAt) {}

public record PendingSanctionApprovalPageResponse(
        List<PendingSanctionApprovalResponse> content, PageMetadata page) {}
```

- [ ] **Step 4: 최소 repository query와 query service를 구현한다.**

```java
@Query(value = """
        select sanction from MemberSanction sanction
        join fetch sanction.supportCase supportCase
        where sanction.status = :status
          and sanction.proposedByOperatorId <> :operatorId
        """,
        countQuery = """
        select count(sanction) from MemberSanction sanction
        where sanction.status = :status
          and sanction.proposedByOperatorId <> :operatorId
        """)
Page<MemberSanction> findApprovalCandidates(
        @Param("status") MemberSanctionStatus status,
        @Param("operatorId") long operatorId,
        Pageable pageable);
```

Service는 authority snapshot을 한 번 읽고 permission·role을 검사한 뒤 `PageRequest.of(page, size, Sort.by(DESC, "proposedAt").and(Sort.by(DESC, "id")))`를 전달한다. 시간은 `ZoneOffset.UTC`로 변환한다.

- [ ] **Step 5: unit test를 실행해 GREEN을 확인한다.**

Run: `cd backend; .\gradlew.bat test --tests com.miriyum.domain.platformoperator.membersupport.PendingMemberSanctionQueryServiceTest`

Expected: PASS.

### Task 3: 실제 MySQL HTTP와 flag OFF 계약

**Files:**
- Modify: `backend/src/main/java/com/miriyum/domain/platformoperator/controller/membersupport/PlatformOperatorMemberSupportController.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/config/PlatformOperatorFeatureFlagIT.java`
- Modify: `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/PlatformOperatorMemberSupportHttpIT.java`

**Interfaces:**
- Consumes: `PendingMemberSanctionQueryService.list(principal, page, size)`
- Produces: authenticated GET response `ApiResponse<PendingSanctionApprovalPageResponse>`와 실제 MySQL·중앙 session 검증 evidence

- [ ] **Step 1: Controller 구현 전에 실제 MySQL fixture의 상태·자기 제안·정렬·페이지 경계 HTTP test를 작성한다.**

서로 다른 제안자가 만든 pending 2건, 현재 운영자의 pending 1건, 이미 APPLIED 1건을 저장한다. `page=0&size=1`, `page=1&size=1`, 빈 마지막 page를 호출해 다른 제안자의 pending 2건만 `proposedAt DESC, id DESC`로 나타나고 `totalElements=2`인지 확인한다. 첫 항목 JSON key set은 정확히 7개여야 하고 `version`은 연결 case row version이어야 한다.

같은 test class에 다음 거부 cases도 먼저 추가한다.

- permission 없는 운영자는 `403 ADMIN_001`
- permission 직접 grant만 있고 `SUPER_ADMIN`이 아니면 `403 ADMIN_001`
- 로그인 후 MySQL `authority_version`을 증가시키면 기존 token 조회는 `401 AUTH_015`
- 로그아웃한 중앙 session의 token은 `401 AUTH_015`
- `page=-1`, `size=0`, `size=101`은 `400`

- [ ] **Step 2: platform operator flag OFF에서 새 method가 conditional Controller에 속하고 bean·route가 노출되지 않는 test를 작성한다.**

```java
assertThat(Arrays.stream(PlatformOperatorMemberSupportController.class.getDeclaredMethods())
        .filter(method -> method.isAnnotationPresent(GetMapping.class))
        .flatMap(method -> Arrays.stream(method.getAnnotation(GetMapping.class).value())))
        .contains("/member-sanctions/pending-additional-approvals");
assertThat(context.getBeansOfType(PlatformOperatorMemberSupportController.class)).isEmpty();
mvc.perform(get("/api/v1/platform-operators/member-sanctions/pending-additional-approvals"))
        .andExpect(status().isNotFound());
```

- [ ] **Step 3: 두 integration class를 실행해 RED를 확인한다.**

Run: `cd backend; .\gradlew.bat integrationTest --tests com.miriyum.domain.platformoperator.membersupport.PlatformOperatorMemberSupportHttpIT --tests com.miriyum.domain.platformoperator.config.PlatformOperatorFeatureFlagIT`

Expected: FAIL because ON HTTP route와 Controller GET method가 아직 없다.

- [ ] **Step 4: 기존 conditional Controller에 GET mapping을 최소 구현한다.**

```java
@GetMapping("/member-sanctions/pending-additional-approvals")
public ApiResponse<PendingSanctionApprovalPageResponse> pendingAdditionalApprovals(
        @AuthenticationPrincipal PlatformOperatorPrincipal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {
    return ApiResponse.success("추가 승인 대기 제재를 조회했습니다.",
            pendingSanctions.list(principal, page, size));
}
```

- [ ] **Step 5: contract와 두 integration class를 실행해 GREEN을 확인한다.**

Run: `cd backend; .\gradlew.bat test --tests com.miriyum.domain.platformoperator.PlatformOperatorOpenApiContractTest`; `cd backend; .\gradlew.bat integrationTest --tests com.miriyum.domain.platformoperator.membersupport.PlatformOperatorMemberSupportHttpIT --tests com.miriyum.domain.platformoperator.config.PlatformOperatorFeatureFlagIT`

Expected: PASS.

### Task 4: focused verification와 publish

**Files:**
- Verify only the allowlisted files

**Interfaces:**
- Consumes: Tasks 1~4 implementation
- Produces: one intentional feature commit, remote branch, Draft PR to `dev`

- [ ] **Step 1: fresh focused unit/contract verification을 실행한다.**

Run:

```powershell
cd backend
.\gradlew.bat test `
  --tests com.miriyum.domain.platformoperator.membersupport.PendingMemberSanctionQueryServiceTest `
  --tests com.miriyum.domain.platformoperator.PlatformOperatorOpenApiContractTest
```

Expected: all selected tests PASS.

- [ ] **Step 2: fresh focused integration verification을 실행한다.**

Run:

```powershell
cd backend
.\gradlew.bat integrationTest `
  --tests com.miriyum.domain.platformoperator.membersupport.PlatformOperatorMemberSupportHttpIT `
  --tests com.miriyum.domain.platformoperator.config.PlatformOperatorFeatureFlagIT
```

Expected: both selected integration classes PASS. 전체 suite는 GitHub CI 확인 대상으로 남긴다.

- [ ] **Step 3: diff와 allowlist를 검증한다.**

Run: `git diff --check`; `git status --short`; `git diff --name-only origin/dev...HEAD`; `git diff --name-only`

Expected: whitespace errors가 없고 모든 변경 파일이 Global Constraints의 allowlist 안에 있다.

- [ ] **Step 4: 구현 파일만 명시적으로 stage하고 커밋한다.**

```powershell
git add -- <explicit allowlisted paths>
git commit -m "feat: add pending sanction approval query"
```

- [ ] **Step 5: 브랜치를 push하고 `dev` 대상 Draft PR을 만든다.**

```powershell
git push -u origin feature/425-pending-sanction-approvals
```

PR 본문에는 `Closes #425`, 보안·최소 필드·검증 결과, 전체 검증은 GitHub CI가 authoritative임을 기록하고, “#405 프론트가 수동 sanction ID 입력을 실제 추가 승인 대기 목록으로 교체할 수 있게 됐다”를 명시한다.
