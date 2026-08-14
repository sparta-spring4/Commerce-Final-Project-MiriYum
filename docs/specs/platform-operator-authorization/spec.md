# 기능 명세: 플랫폼 운영자 권한·재인증·고위험 명령 공통 기반

> 문서 상태: 구현 승인 기준
> 적용 단계: 고도화
> 소유 도메인: platformoperator
> 관련 정책 ID: ADMIN-001, ADMIN-002
> 소유 Issue: #276
> 선행 계약: #275
> Blocks: #277~#282
> OpenAPI: `docs/specs/platform-operator-authorization/openapi.yaml`
> Audience: `docs/specs/platform-operator-openapi.yaml`
> 최종 승인일: 2026-08-14

## 결과와 경계

#275의 독립 `platform-operator` principal과 중앙 세션 위에서 역할·세부 권한, 사건 배정과 현재 비밀번호 재인증을 고위험 명령의 공통 서버 계약으로 제공한다. #277~#282는 이 계약을 소비하고 인증 namespace, 권한 판정이나 승인 소비를 중복 구현하지 않는다.

포함 범위는 고정 역할·권한 catalog, 계정별 grant와 `authority_version`, 중앙 사건 배정 검증, 목적·대상·세션·만료에 결속된 일회 승인, 고위험 명령 guard, 감사 context와 마지막 슈퍼관리자 보호 판정이다. WebAuthn, 실제 업무 명령, 운영자 관리 HTTP API, 통합 감사 조회 API와 frontend는 제외한다.

## 역할과 세부 권한 catalog

역할은 권한 묶음이며 최종 판정은 항상 세부 권한으로 한다.

| 역할 | 기본 권한 |
| --- | --- |
| `SUPER_ADMIN` | `OPERATOR_CREATE`, `OPERATOR_AUTHORITY_MANAGE`, `OPERATOR_SUSPEND`, `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE`, `BREAK_GLASS_APPROVE` |
| `ONBOARDING_REVIEWER` | `ONBOARDING_REVIEW`, `ONBOARDING_EVIDENCE_READ` |
| `MEMBER_SUPPORT_OPERATOR` | `MEMBER_READ_MINIMAL`, `MEMBER_RECOVERY`, `ACCOUNT_APPEAL_REVIEW` |
| `ENFORCEMENT_OPERATOR` | `ACCOUNT_SANCTION`, `STORE_READ_MINIMAL`, `STORE_SANCTION` |
| `PAYMENT_RECOVERY_OPERATOR` | `PAYMENT_RECOVERY_EXECUTE` |
| `OPERATIONS_MONITOR` | `OPERATIONS_MONITOR_READ` |
| `AUDIT_READER` | `AUDIT_READ` |
| `INCIDENT_RESPONDER` | `INCIDENT_RESPOND` |

`SUPER_ADMIN`도 업무상 필요가 없는 회원·입점 증빙·감사 원문 조회 권한을 자동으로 얻지 않는다. 개인정보 대량 조회·내보내기, 결제수단·비밀과 광범위 민감정보 권한은 catalog에 없다.

## 중앙 권한과 권한 회수

- `platform_operator_role_grants`와 `platform_operator_permission_grants`가 계정별 역할·직접 권한 grant의 원본이다.
- 역할·권한 변경은 플랫폼 운영자 계정 행을 잠그고 grant 변경, `authority_version`과 `session_version` 증가를 같은 MySQL 트랜잭션에서 확정한다.
- 모든 보호 요청은 #275처럼 현재 계정 version과 중앙 세션을 다시 검증한다. 구 version principal은 `401 AUTH_015`로 거부한다.
- 고위험 명령 guard도 계정 행을 잠그므로 권한 회수와 명령은 같은 계정에 대해 직렬화된다. 회수가 먼저 확정되면 어느 인스턴스도 구 version 요청을 허용하지 않는다.

## 사건 배정

`admin_case_assignments`는 사건 유형, 공개 사건 ID, 사건 version, 담당 운영자, 상태와 만료를 소유한다. 배정은 `ASSIGNED` 상태이고 만료 전이며 요청의 사건 version·담당 운영자와 정확히 일치할 때만 유효하다. 재배정, 종결 또는 version 증가 뒤 구 배정은 거부한다.

이 원장은 담당자 증명만 제공한다. #277~#282의 실제 업무 사건 상태·결정·멱등성은 각 도메인이 소유하며 platformoperator는 다른 도메인의 Entity·Repository를 참조하지 않는다.

## 현재 비밀번호 재인증과 일회 승인

1. 정상 플랫폼 운영자 세션이 현재 비밀번호, 목적, 대상 유형과 대상 ID를 제출한다.
2. 서버는 현재 계정 상태·비밀번호·세션과 `authority_version`을 다시 확인한다.
3. 256-bit 이상 CSPRNG opaque 승인값을 한 번만 반환한다.
4. MySQL에는 승인 digest, 계정, 목적, 대상, 세션 fingerprint, 권한 version, 발급·만료·소비 시각만 저장한다.
5. 승인은 발급부터 정확히 5분 동안 유효하고 연장되지 않는다.
6. 목적·대상·세션·권한 version 가운데 하나라도 다르거나 만료·소비됐으면 거부한다.

현재 비밀번호, 승인 원문, 토큰과 원문 세션 ID는 로그·감사·DB에 남기지 않는다. WebAuthn 도입 시점·등록·분실 복구는 ADMIN-002의 TODO로 유지한다.

재인증 비밀번호 검증은 로그인과 동일한 `PLATFORM_OPERATOR` 계정별 `LoginDelayGuard` 예산을 사용한다. 따라서 어느 인스턴스에서 발생한 로그인·재인증 실패든 MySQL의 같은 실패 횟수와 지연 단계를 공유하며, 성공하면 같은 예산을 초기화한다. 재인증 성공·실패는 비밀번호나 승인 원문 없이 `REAUTHENTICATION` 인증 사건으로 append한다.

`currentPassword`의 wire 검증은 NFC 정규화 후 Unicode code point 8~64개를 기준으로 한다. UTF-16 surrogate pair나 NFD 결합 문자가 포함돼도 활성 비밀번호 정본과 동일한 기준으로 판단한다.

세션 fingerprint HMAC은 JWT 서명 secret을 재사용하지 않고 `miriyum.platform-operator.reauthentication-fingerprint-secret` 전용 secret을 사용한다. 운영 배포 환경 변수 `MIRIYUM_PLATFORM_OPERATOR_REAUTH_FINGERPRINT_SECRET`은 32자 이상이어야 하며 production compose에서 필수다.

## 고위험 명령 guard

소비 도메인은 자신의 활성 MySQL 명령 트랜잭션 안에서 `HighRiskCommandGuard.authorize()`를 호출한다. guard는 다음 순서를 지킨다.

1. 플랫폼 운영자 계정 행 잠금
2. principal과 현재 계정·세션·권한 version 일치
3. 현재 세부 권한 존재
4. 사건 배정의 담당자·상태·version·만료
5. 승인 digest와 목적·대상·세션 fingerprint·권한 version·만료
6. 승인 조건부 소비

권한·배정·재인증 중 하나라도 없으면 명령을 허용하지 않는다. 성공하면 행위자, 역할·권한 snapshot, 권한 version, 사건, 목적, 대상, 승인 fingerprint와 correlation ID를 담은 `AdminAuditContext`를 반환한다. 승인과 세션 원문은 포함하지 않는다.

## 마지막 슈퍼관리자 보호

`LastSuperAdminPolicy.assertRemovable(targetOperatorId)`는 `platform_operator_authority_guard` singleton 행을 잠근 뒤 활성 계정의 `SUPER_ADMIN` 역할 수를 계산한다. 대상 역할 회수 또는 계정 중지가 활성 슈퍼관리자 0명을 만들면 거부한다. #282는 자신의 변경 트랜잭션에서 이 계약을 호출해야 한다.

## HTTP 계약

| Method | Path | 인증 | 결과 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/platform-operators/reauthentication-approvals` | 정상 #275 Bearer principal과 현재 비밀번호 | 5분 일회 승인 발급 |

고위험 명령은 `X-Admin-Reauthentication` 헤더로 승인값을 전달한다. 목적·대상·필요 권한은 endpoint가 서버 측에서 구성하며 클라이언트 입력 역할·권한으로 승격하지 않는다. 별도 feature flag를 만들지 않고 #275의 `miriyum.platform-operator.enabled`를 사용하며 OFF에서는 Controller 부재의 실제 404를 반환한다.

## 오류 계약

| 상황 | HTTP | code |
| --- | --- | --- |
| 권한·배정·승인 누락, 불일치, 만료 또는 재사용 | 403 | `ADMIN_001` |
| 현재 비밀번호 불일치 | 401 | `ADMIN_002` |
| 세션 또는 권한 version 불일치 | 401 | `AUTH_015` |
| 최초 비밀번호 변경 전 제한 세션 | 403 | `AUTH_012` |
| 마지막 활성 슈퍼관리자 제거 | 409 | `ADMIN_003` |
| 중앙 권한·승인 저장소 장애 | 503 | `COMMON_012` |
| feature flag OFF | 404 | MVC 404 |

승인 재사용, 다른 목적·대상·세션 사용과 만료는 외부에서 구분하지 않고 모두 `ADMIN_001`로 수렴한다.

## 데이터와 migration

최신 `dev`의 V39 다음 migration은 `V40__create_platform_operator_authorization.sql`이다. 새 테이블은 역할 grant, 직접 권한 grant, 사건 배정, 재인증 승인과 singleton 권한 guard다. 기존 migration이나 #275 인증 테이블을 다시 작성하지 않는다.

만료·소비된 재인증 승인 원장은 이번 PR에서 자동 삭제하지 않는다. `target_id`와 session fingerprint 보존 기간 및 정리 작업은 운영 데이터 보존 정책을 정한 후 후속 작업으로 추가한다.

## 변경 allowlist

이 기능 PR은 다음 경로만 변경할 수 있다.

- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/auth/PlatformOperatorAuthorizationController.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/entity/{AdminCaseAssignment,PlatformOperatorAuthorityGuard,PlatformOperatorPermissionGrant,PlatformOperatorReauthenticationApproval,PlatformOperatorRoleGrant}.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/{AdminCaseAssignmentStatus,AdminCaseType,AdminCommandPurpose,AdminTargetType,PlatformOperatorAuthEventType,PlatformOperatorPermission,PlatformOperatorRole}.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/exception/AdminAuthorizationErrorCode.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/repository/{AdminCaseAssignmentRepository,PlatformOperatorAuthorityGuardRepository,PlatformOperatorPermissionGrantRepository,PlatformOperatorReauthenticationApprovalRepository,PlatformOperatorRoleGrantRepository}.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/service/{AdminCaseAssignmentManager,AdminCaseAssignmentService,AdminCaseAssignmentVerifier,HighRiskCommandGuard,LastSuperAdminPolicy,OperatorAuthorityReader,OperatorAuthorityService,ReauthenticationService}.java`
- `backend/src/main/java/com/miriyum/global/validation/NfcCodePointSize.java`
- `backend/src/main/resources/{application.yml,db/migration/V40__create_platform_operator_authorization.sql}`
- `backend/src/test/java/com/miriyum/domain/platformoperator/**`
- `backend/src/test/java/com/miriyum/global/config/PlatformOperatorReauthenticationConfigurationTest.java`
- `deploy/{.env.example,docker-compose.prod.yml,local/.env.example,local/docker-compose.dev.yml}`
- `docs/{02-users-and-permissions.md,05-functional-requirements.md,07-data-and-api-contracts.md,09-quality-operations-and-rules.md}`
- `docs/service-policies/15-admin-operation.md`
- `docs/specs/{README.md,platform-operator-openapi.yaml}`
- `docs/specs/platform-operator-authorization/{spec.md,openapi.yaml}`
- `redocly.yaml`

`docs/superpowers/**`, 다른 도메인의 Entity·Repository, 기존 migration과 실제 #277~#282 업무 명령은 allowlist 밖이다.

## 테스트와 인수 조건

- 역할 mapping과 직접 grant 합성을 검증한다.
- 권한·배정·재인증 각각의 누락이 고위험 명령 거부로 수렴한다.
- 실제 MySQL에서 권한 회수와 명령 경합 뒤 구 version 요청을 거부한다.
- 같은 승인값의 동시 소비는 한 요청만 성공한다.
- 다른 목적·대상·세션과 만료·소비된 승인의 재사용을 거부한다.
- 사건 재배정·종결·version 증가 뒤 구 배정을 거부한다.
- 동시 슈퍼관리자 제거 요청이 활성 슈퍼관리자 0명을 만들지 못한다.
- HTTP namespace·권한·기능 flag와 OpenAPI runtime drift를 검증한다.
- `backend.test`, `backend.integration-test`, `backend.build`, OpenAPI lint와 `git diff --check`를 실행한다.

시간은 주입한 `Clock`으로 제어하고 `sleep`을 사용하지 않는다. 동시성 테스트는 barrier/latch와 실제 MySQL을 사용한다.

## #277~#282 공개 인계

- #277: 입점 권한, 증빙 최소 조회 권한, 사건 배정과 고위험 결정 guard
- #278: 회원 최소 조회·복구·제재·이의 권한
- #279: 매장 최소 조회·제재 권한
- #280: 운영 모니터링 권한과 민감 필드 사건 배정 검증
- #281: 결제 복구 실행·고액 승인 권한과 고위험 guard
- #282: 운영자 관리 권한, 권한 version 변경, 마지막 슈퍼관리자 판정과 감사 context

각 후속 기능은 명령별 목적·대상·사건 유형, 도메인 상태 전이, 멱등성, 승인자 분리와 감사 writer를 자신의 spec/OpenAPI에서 확정한다.
