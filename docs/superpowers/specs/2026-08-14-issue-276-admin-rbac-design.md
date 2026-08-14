# Issue #276 운영자 RBAC·재인증·고위험 명령 공통 기반 설계

> 상태: 승인된 설계 이력
> 소유 Issue: #276
> 적용 단계: 고도화
> 선행 계약: #275 / PR #322 (`dev` 병합 완료)
> 정책: ADMIN-001, ADMIN-002의 확정 안전 기본선
> 활성 정본 예정 경로: `docs/specs/platform-operator-authorization/`

## 목표

#275가 제공한 독립 `platform-operator` principal, 중앙 세션, 계정 상태와 `authority_version` 검증 위에 역할·세부 권한, 사건 배정, 현재 비밀번호 재인증, 목적 제한 일회 승인과 고위험 명령 guard를 제공한다. #277~#282는 이 공통 계약만 소비하고 플랫폼 운영자 인증, 권한 판정 또는 승인 소비를 중복 구현하지 않는다.

## 범위

### 포함

- 코드에 고정된 역할·세부 권한 catalog
- MySQL의 계정별 역할·직접 권한 grant와 중앙 사건 배정
- 단조 증가하는 `authority_version`을 통한 모든 인스턴스의 즉시 권한 회수
- 현재 플랫폼 운영자 비밀번호 재인증
- 목적·대상·세션·권한 version·만료에 결속된 5분 일회 승인
- 권한·사건 배정·재인증을 함께 검증하는 고위험 명령 guard
- 비밀 원문이 없는 `AdminAuditContext`
- 마지막 활성 슈퍼관리자를 보호하는 공통 MySQL 판정 계약
- 재인증 승인 발급 HTTP API와 플랫폼 운영자 OpenAPI audience

### 제외

- WebAuthn 등록·인증·분실 복구
- #277~#282 업무 도메인의 실제 심사·제재·복구·조회·계정 관리 명령
- 운영자 계정 생성·중지·권한 변경 HTTP API와 통합 감사 조회 API
- 슈퍼관리자라는 이유만으로 허용하는 포괄 개인정보·결제수단·비밀 조회
- frontend 화면과 권한 메뉴

## 선택한 접근

역할과 세부 권한은 Java enum catalog로 고정하고 MySQL은 계정별 grant와 version만 소유한다. DB 동적 catalog는 런타임 오타와 인스턴스 드리프트 위험 때문에 사용하지 않는다. 역할만 저장하고 권한을 암묵적으로 판정하는 방식도 세부 권한 회수와 최소 권한 원칙을 충족하지 못하므로 사용하지 않는다.

역할은 편의상 권한을 묶지만 최종 인가는 항상 세부 권한으로 판정한다. 직접 권한 grant는 역할 묶음을 불필요하게 부여하지 않고 예외적인 최소 권한을 줄 때만 사용한다.

## 역할과 세부 권한 catalog

### 역할

- `SUPER_ADMIN`
- `ONBOARDING_REVIEWER`
- `MEMBER_SUPPORT_OPERATOR`
- `ENFORCEMENT_OPERATOR`
- `PAYMENT_RECOVERY_OPERATOR`
- `OPERATIONS_MONITOR`
- `AUDIT_READER`
- `INCIDENT_RESPONDER`

### 세부 권한

- 운영자 관리: `OPERATOR_CREATE`, `OPERATOR_AUTHORITY_MANAGE`, `OPERATOR_SUSPEND`
- 입점: `ONBOARDING_REVIEW`, `ONBOARDING_EVIDENCE_READ`
- 회원: `MEMBER_READ_MINIMAL`, `MEMBER_RECOVERY`, `ACCOUNT_SANCTION`, `ACCOUNT_APPEAL_REVIEW`
- 매장: `STORE_READ_MINIMAL`, `STORE_SANCTION`
- 모니터링: `OPERATIONS_MONITOR_READ`
- 결제: `PAYMENT_RECOVERY_EXECUTE`, `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE`
- 감사·장애: `AUDIT_READ`, `INCIDENT_RESPOND`, `BREAK_GLASS_APPROVE`

`SUPER_ADMIN`도 업무상 필요가 없는 모든 읽기 권한을 자동으로 얻지 않는다. 운영자 생명주기와 고위험 승인에 필요한 권한만 묶고, 원문 PII·대량 내보내기·결제수단·비밀 조회 권한은 catalog에 두지 않는다.

## 영속 모델

새 Flyway migration은 최신 `dev`의 V39 다음인 `V40__create_platform_operator_authorization.sql`로 고정한다.

### 권한 grant

- `platform_operator_role_grants`: 계정 ID, 역할, 부여 시각
- `platform_operator_permission_grants`: 계정 ID, 직접 권한, 부여 시각
- 같은 계정·역할 또는 계정·권한의 중복 grant를 유일 제약으로 막는다.
- 권한 변경자는 `platform_operator_accounts` 행을 먼저 잠그고 grant 변경과 `authority_version`, `session_version` 증가를 같은 트랜잭션에서 확정한다.
- 권한 조회는 cache를 사용하지 않고 현재 MySQL version과 grant를 읽는다. 구 version principal은 #275의 `AUTH_015`로 실패한다.

### 사건 배정

- `admin_case_assignments`: 사건 유형, 사건 ID, 사건 version, 담당 운영자 ID, 상태, 만료 시각, row version
- 공개 DTO는 scalar만 사용하고 업무 도메인의 Entity·Repository를 참조하지 않는다.
- `ASSIGNED`이며 만료 전이고 요청의 사건 version과 정확히 일치할 때만 유효하다.
- 재배정·종결·version 증가 뒤 이전 배정은 모든 인스턴스에서 거부한다.

### 재인증 승인

- `platform_operator_reauthentication_approvals`: 승인 digest, 계정 ID, 목적, 대상 유형·ID, 세션 fingerprint, 권한 version, 발급·만료·소비 시각
- 승인 원문은 256-bit 이상 CSPRNG opaque 값으로 한 번만 반환하고 저장하지 않는다.
- 세션 ID 원문은 저장하거나 감사하지 않고 서버 비밀키 기반 fingerprint만 사용한다.
- TTL은 발급 시각부터 5분이며 연장하지 않는다.
- 승인 digest, 결속 필드, 미소비 상태와 만료를 포함한 조건부 `UPDATE`로 한 요청만 소비한다.

### 마지막 슈퍼관리자 보호

- `platform_operator_authority_guard` singleton 행을 사용해 슈퍼관리자 제거·중지 판정을 직렬화한다.
- 판정은 활성 계정 중 `SUPER_ADMIN` 역할이 있는 수를 같은 트랜잭션에서 계산한다.
- 대상 변경 결과가 활성 슈퍼관리자 0명을 만들면 `409 ADMIN_003`으로 거부한다.
- #282는 이 판정을 우회하지 않고 권한 변경 또는 계정 중지 트랜잭션 안에서 호출한다.

## 공개 Java 계약

- `OperatorAuthorityReader.currentAuthority(operatorId)`: 현재 역할·유효 세부 권한·`authorityVersion`을 반환한다.
- `AdminCaseAssignmentVerifier.verify(request)`: 사건 유형·ID·version·담당자·만료를 검증한다.
- `ReauthenticationService.issue(principal, command)`: 현재 비밀번호와 #275 세션을 확인하고 일회 승인 원문과 만료 시각을 반환한다.
- `HighRiskCommandGuard.authorize(request)`: 활성 외부 트랜잭션 안에서 모든 전제조건을 검증하고 승인을 소비한다.
- `AdminAuditContext`: 행위자 ID, 역할·권한 snapshot, 권한 version, 사건, 목적, 대상, 승인 fingerprint, 요청 상관관계 ID를 반환한다.
- `LastSuperAdminPolicy.assertRemovable(targetOperatorId)`: singleton lock과 중앙 count로 제거 가능 여부를 판정한다.

`HighRiskCommandGuard.authorize()`는 소비 도메인의 명령 트랜잭션에 참여해야 한다. guard가 반환된 뒤에도 계정 행 잠금은 소비 명령과 감사 사건이 commit될 때까지 유지되어 권한 회수와 명령 실행이 서로 끼어들지 못한다.

## HTTP와 OpenAPI

### 승인 발급

- `POST /api/v1/platform-operators/reauthentication-approvals`
- 인증: #275의 정상 `platform-operator` Bearer principal
- 요청: `currentPassword`, `purpose`, `targetType`, `targetId`
- 응답: `approval`, `expiresAt`
- 승인값은 응답 한 번에만 포함하고 로그·감사·DB에 원문을 남기지 않는다.

### 고위험 명령 소비

- 소비 API는 일회 승인값을 `X-Admin-Reauthentication` 헤더로 받는다.
- 소비 Service는 endpoint가 정한 목적·대상을 서버 측 `HighRiskCommandRequest`로 구성한다. 클라이언트가 보낸 목적이나 권한 이름으로 판정을 승격하지 않는다.
- 사건 version과 correlation ID, 각 도메인의 멱등 키는 소비 기능의 OpenAPI가 소유한다.

### feature flag

별도 flag를 만들지 않고 #275의 `miriyum.platform-operator.enabled`를 사용한다. OFF에서는 승인 Controller가 생성되지 않고 namespace가 실제 MVC 404로 수렴한다.

## 요청 흐름과 동시성

1. #275 필터가 Access JWT, 계정 상태·version과 Valkey 중앙 세션을 검증한다.
2. 승인 발급 요청은 현재 비밀번호를 검증하고 목적·대상·현재 세션 fingerprint·현재 `authority_version`에 결속된 digest만 저장한다.
3. 소비 도메인은 활성 MySQL 트랜잭션에서 guard를 호출한다.
4. guard는 플랫폼 운영자 계정 행을 `FOR UPDATE`로 잠근다.
5. principal version과 현재 계정 version, 현재 역할·세부 권한을 검증한다.
6. 사건 배정의 담당자·상태·version·만료를 검증한다.
7. 승인 digest와 목적·대상·세션 fingerprint·권한 version·만료를 비교하고 조건부 갱신으로 소비한다.
8. 성공하면 원문 비밀이 없는 `AdminAuditContext`를 반환한다.
9. 소비 도메인은 같은 트랜잭션에서 실제 명령, 도메인 감사 사건과 멱등 결과를 확정한다.

권한 회수는 같은 계정 행 잠금을 사용하므로 진행 중인 명령 앞이나 뒤에 직렬화된다. 회수가 먼저 commit되면 구 version 요청은 모든 인스턴스에서 거부된다. guard가 먼저 권한을 확정하면 그 명령 transaction이 끝난 뒤 회수가 진행된다.

## 실패 계약

- 권한·사건 배정·재인증 승인 중 하나라도 없거나 결속이 틀리면 `403 ADMIN_001`이다.
- 현재 비밀번호 불일치는 `401 ADMIN_002`다.
- 세션 또는 권한 version 불일치는 기존 #275의 `401 AUTH_015`다.
- 마지막 활성 슈퍼관리자를 제거하려는 경합은 `409 ADMIN_003`이다.
- 중앙 권한·승인 저장소 장애는 `503 COMMON_012`로 실패 폐쇄한다.
- 승인 재사용, 다른 목적·대상·세션 사용과 만료는 외부에서 원인을 구분하지 않고 모두 `403 ADMIN_001`로 응답한다.
- feature flag OFF에서는 승인 경로를 포함한 플랫폼 운영자 namespace가 실제 404다.

## 보안과 개인정보

- 현재 비밀번호, 승인 원문, Access/Refresh Token과 세션 ID 원문을 로그·감사·DB에 남기지 않는다.
- 비밀번호 검증 실패는 계정의 다른 상태나 승인 가능 여부를 공개하지 않는다.
- 승인값은 Bearer 자격이 아니라 현재 플랫폼 운영자 세션에 추가로 결속된 단일 명령 증명이다.
- 관리자 입력 역할·권한·계정 유형·행위자 ID를 신뢰하지 않는다.
- 인증·권한·사건·승인 저장소 중 하나라도 확인할 수 없으면 상태 변경과 민감 조회를 허용하지 않는다.

## 테스트 전략

### 단위·slice

- 역할별 권한 mapping과 직접 grant 합성
- 세션·권한 version 불일치
- 승인 목적·대상·세션·version·만료의 각 결속 필드
- 권한·배정·재인증 각각의 누락이 동일한 외부 거부로 수렴함
- 감사 context가 승인·세션·비밀번호 원문을 포함하지 않음
- 마지막 슈퍼관리자 판정 계약
- HTTP namespace, validation, 성공·오류 envelope, 기능 flag OFF 404
- Controller method/path/operationId/status/DTO와 OpenAPI drift

### 실제 MySQL Testcontainers

- V40 제약과 FK
- 역할·직접 권한 grant 및 `authority_version` 증가 원자성
- 권한 회수와 고위험 명령 경합의 잠금 순서
- 같은 승인값을 두 인스턴스가 동시에 소비할 때 단일 승자
- 목적·대상·세션이 다른 재사용 공격 거부
- 사건 재배정·종결·version 증가와 명령 경합
- 두 슈퍼관리자의 동시 제거 요청이 0명을 만들지 못함

### 전체 검증

- `backend.test`
- `backend.integration-test`
- `backend.build`
- OpenAPI lint와 architecture/contract 테스트
- `git diff --check`

시간 테스트는 주입한 `Clock`으로 제어하고 `sleep`을 사용하지 않는다. 경합 테스트는 barrier/latch를 사용한다.

## 정확한 변경 파일 allowlist

### 활성 정본·계약

- `docs/02-users-and-permissions.md`
- `docs/05-functional-requirements.md`
- `docs/07-data-and-api-contracts.md`
- `docs/09-quality-operations-and-rules.md`
- `docs/service-policies/15-admin-operation.md`
- `docs/specs/README.md`
- `docs/specs/platform-operator-authorization/spec.md`
- `docs/specs/platform-operator-authorization/openapi.yaml`
- `docs/specs/platform-operator-openapi.yaml`
- `redocly.yaml`

### 설계·계획 이력

- `docs/superpowers/specs/2026-08-14-issue-276-admin-rbac-design.md`
- `docs/superpowers/plans/2026-08-14-issue-276-admin-rbac.md`

### Backend 계약·설정·migration

- `backend/ai/implementation-guardrails.md`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V40__create_platform_operator_authorization.sql`
- `backend/src/main/java/com/miriyum/domain/platformoperator/config/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/authorization/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/entity/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/exception/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/repository/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/service/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/session/PlatformOperatorPrincipal.java`

### 테스트

- `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/**`

기존 migration, 일반 사용자·매장 운영자 도메인, 다른 업무 도메인의 Entity·Repository·Service·Controller와 frontend 파일은 변경하지 않는다. 구현 중 allowlist 밖의 파일이 필요하면 편집을 중단하고 범위를 다시 승인받는다.

## #277~#282 인계

- #277: `ONBOARDING_REVIEW`, `ONBOARDING_EVIDENCE_READ`, 사건 배정과 고위험 결정 guard
- #278: `MEMBER_READ_MINIMAL`, `MEMBER_RECOVERY`, `ACCOUNT_SANCTION`, `ACCOUNT_APPEAL_REVIEW`
- #279: `STORE_READ_MINIMAL`, `STORE_SANCTION`
- #280: `OPERATIONS_MONITOR_READ`와 민감 필드용 사건 배정 검증
- #281: `PAYMENT_RECOVERY_EXECUTE`, `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE`, 승인자 분리와 고위험 guard
- #282: 운영자 관리 권한, 권한 version 증가, `LastSuperAdminPolicy`, `AdminAuditContext`

후속 기능은 자신의 실제 도메인 상태·version·멱등성·감사 원장을 소유한다. #276의 사건 배정은 담당자 증명만 제공하고 각 업무 사건의 상태 머신을 대신하지 않는다.

## 남은 제약

- ADMIN-002의 WebAuthn 도입 시점·등록·분실 복구는 계속 TODO이며 현재 비밀번호 승인을 WebAuthn으로 표현하지 않는다.
- ADMIN-009의 미확정 감사 유형별 보존기간과 최종 법률 문구를 정하지 않는다.
- 개인정보 대량 조회·내보내기와 광범위 민감정보 접근 권한을 추가하지 않는다.
- #282가 운영자 권한 변경 API를 구현하기 전까지 grant를 변경하는 공개 HTTP 경로는 없다.
- #277~#282가 명령별 목적·대상·사건 유형과 감사 writer를 자신의 spec/OpenAPI에서 확정해야 실제 업무 명령에 연결할 수 있다.
