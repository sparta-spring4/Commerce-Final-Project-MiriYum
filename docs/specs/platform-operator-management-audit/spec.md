# 기능 명세: 운영자 계정·권한 관리 및 감사 이력

> 문서 상태: 구현 승인 기준
> 적용 단계: 고도화
> 소유 도메인: platformoperator
> 관련 정책 ID: ADMIN-001, ADMIN-002, ADMIN-009
> 소유 Issue: #282
> 선행 계약: #275, #276
> OpenAPI: `docs/specs/platform-operator-management-audit/openapi.yaml`
> Audience: `docs/specs/platform-operator-openapi.yaml`
> 최종 승인일: 2026-08-14

## 결과와 경계

부트스트랩으로 존재하는 단일 슈퍼관리자가 비슈퍼관리자 플랫폼 운영자를 생성·중지하고 담당 도메인의 역할·직접 권한을 변경한다. 중요 명령과 감사 조회는 수정·삭제할 수 없는 통합 감사 이력에 남기고, 잘못된 사건은 원 사건을 보존한 연결 보정 사건으로만 바로잡는다.

포함 범위는 비슈퍼관리자 계정 생성·중지, 역할·직접 권한 전체 교체, 단일 슈퍼관리자 보호, 관리·인증 감사 통합 조회, 조회 감사와 연결 보정 사건이다. 슈퍼관리자 bootstrap·추가·교체·중지·강등, 계정 재활성화, 임시 비밀번호 재발급, frontend, 감사 내보내기와 보존·파기 자동화는 제외한다.

## 단일 슈퍼관리자 불변식

- `SUPER_ADMIN`은 배포 전 별도 bootstrap 절차로 생성된 정확히 한 계정만 보유한다. bootstrap 방법과 자격 증명은 이 HTTP API의 범위가 아니다.
- #282 API는 슈퍼관리자를 생성하거나 기존 슈퍼관리자의 상태·역할·직접 권한을 변경하지 않는다.
- MySQL은 `SUPER_ADMIN` role grant를 최대 하나만 허용한다. 관리 명령은 활성 슈퍼관리자가 정확히 한 명이 아니면 실패 폐쇄한다.
- 생성·권한 변경 요청은 `SUPER_ADMIN` 역할과 `OPERATOR_CREATE`, `OPERATOR_AUTHORITY_MANAGE`, `OPERATOR_SUSPEND`, `PAYMENT_RECOVERY_HIGH_VALUE_APPROVE`, `BREAK_GLASS_APPROVE` 직접 grant를 거부한다.
- 일반 운영자는 담당 도메인 역할과 비핵심 직접 권한만 받는다. 일반 운영자·일반 사용자·매장 운영자는 운영자 계정이나 슈퍼관리자를 만들 수 없다.
- ADMIN-001의 이중 승인 문구는 교체되지 않는 단일 슈퍼관리자 모델에는 적용하지 않는다. DB singleton, 자기 변경 전면 금지, 목적 결속 재인증과 불변 감사가 안전 경계다.

## 관리 명령

정상 #275 세션의 단일 슈퍼관리자만 호출할 수 있다. 서비스는 중앙 `SUPER_ADMIN` role과 현재 authority/session version, #276의 사건 배정과 목적·대상 결속 일회 재인증을 같은 명령 트랜잭션에서 검증·소비한다.

| 명령 | 필요 권한 | 사건 | 재인증 목적·대상 |
| --- | --- | --- | --- |
| 계정 생성 | `OPERATOR_CREATE` | `OPERATOR_MANAGEMENT` | `OPERATOR_CREATION`·`provisioningId` |
| 권한 전체 교체 | `OPERATOR_AUTHORITY_MANAGE` | `OPERATOR_MANAGEMENT` | `OPERATOR_AUTHORITY_CHANGE`·account ID |
| 계정 중지 | `OPERATOR_SUSPEND` | `OPERATOR_MANAGEMENT` | `OPERATOR_SUSPENSION`·account ID |

### 생성

- 요청은 표준 UUID `provisioningId`, 이메일, 표시 이름, 임시 비밀번호, 초기 역할, 초기 직접 권한과 구조화 사유를 포함한다.
- `provisioningId`는 재인증 target과 멱등 식별에 함께 결속한다. 같은 식별자와 같은 정규화 요청은 최초 결과로 수렴하고 다른 요청은 `COMMON_007`로 거부한다.
- 임시 비밀번호는 AUTH-006 정책으로 검증한 뒤 즉시 해시한다. 원문은 응답·로그·감사·이벤트·DB에 저장하지 않는다.
- 확인된 운영자에게 저장소 밖의 일회성 안전 채널로 전달해야 한다. API는 전달 채널을 구현하거나 평문 전달 증거를 보관하지 않는다.
- 만료 시각은 #275의 필수 중앙 `temporary-password.validity` 설정으로 계산하며 새 기본 수치를 만들지 않는다.
- 계정, 초기 grant와 관리 감사 사건은 한 MySQL 트랜잭션에서 확정한다.

### 권한 전체 교체

- 요청한 역할·직접 권한 집합이 최종 desired state다.
- 대상 계정과 singleton guard를 잠근 뒤 금지 grant와 슈퍼관리자 자기 변경을 검사한다.
- 실제 grant가 바뀐 경우에만 `authority_version`과 `session_version`을 각각 한 번 증가시키고 미사용 재인증 승인과 기존 세션을 회수한다.
- 변경 전후 역할·직접 권한의 정렬된 이름 snapshot과 구조화 사유만 감사한다.

### 중지

- 대상은 활성 비슈퍼관리자여야 한다. 같은 멱등 요청은 최초 결과로 수렴한다.
- 상태를 `SUSPENDED`로 변경하고 `session_version`을 증가시킨 뒤 미사용 재인증 승인과 중앙 세션을 회수한다.
- MySQL 변경 뒤 Valkey 회수가 실패하면 `COMMON_012`로 실패 폐쇄한다. 증가한 version 때문에 이전 세션은 다시 유효해지지 않는다.

## 불변 감사 원장

- #275 `platform_operator_auth_events`는 유지하며 `AUTH:{numericId}`로 읽는다.
- #282 `platform_operator_audit_events`는 관리 명령, 감사 조회와 보정 사건을 append하며 `ADMIN:{numericId}`로 읽는다.
- 기존 원장을 수정·삭제하거나 한 테이블로 복사하지 않는다. 통합 조회는 두 원장의 안전 projection을 합성한다.
- 신규 사건은 action, outcome, actor ID, 당시 authority version·역할·권한, target type·opaque ID, 허용된 전후 snapshot, 구조화 reason, correlation ID, 원 사건 source·ID와 중앙 시각만 저장한다.
- 비밀번호·토큰·세션·승인 원문, 결제수단, 증빙, 원문 개인정보, 자유형 JSON과 전체 대상 목록을 저장하지 않는다.
- V43 `BEFORE UPDATE`·`BEFORE DELETE` trigger가 직접 SQL 변경·삭제도 거부한다. UPDATE·DELETE repository는 만들지 않는다.
- ADMIN-009 공통 보존기간은 미정이다. retention 열, TTL, cleanup job, 파기 API와 자동 삭제를 만들지 않는다.

## 최소 권한 감사 조회

- 조회자는 활성 `AUDIT_READER` 역할 또는 직접 `AUDIT_READ`, 유효한 `AUDIT_REVIEW` 사건 배정과 필수 구조화 사유를 모두 가져야 한다. `SUPER_ADMIN`만으로 조회 권한을 얻지 않는다.
- 검색은 발생 시각 역순 고정이며 page/size, source, action, outcome, actor ID, target type·ID, occurred-from/to와 original event key만 허용한다.
- 상세 조회도 같은 검증을 반복한다. 자유형 검색·원문 payload 조회·내보내기는 없다.
- 유효한 플랫폼 운영자 세션에서 발생한 허용·거부 조회를 `AUDIT_SEARCH` 또는 `AUDIT_DETAIL_READ`로 별도 `REQUIRES_NEW` 트랜잭션에 append한다. 인증 전 거부는 #275가 소유한다.
- 조회 감사 저장에 실패하면 조회 결과를 반환하지 않는다.

## 연결 보정 사건

- 단일 슈퍼관리자, `OPERATOR_AUTHORITY_MANAGE`, `AUDIT_REVIEW` 배정과 `AUDIT_CORRECTION` 재인증 승인을 요구한다.
- `AUTH:*` 또는 `ADMIN:*` 원 사건 하나와 구조화 보정 사유, 허용된 대체 action·outcome·target·reason 필드만 받는다.
- 원 사건은 수정하지 않고 새 `AUDIT_CORRECTION` 사건이 original source·ID를 연결한다.
- 반복 보정도 최초 원 사건을 직접 참조하고 시간순으로 모두 조회한다. actor, occurredAt, correlation ID와 원문 인증 사건 필드는 보정할 수 없다.

## HTTP 계약

| Method | Path | 결과 |
| --- | --- | --- |
| `POST` | `/api/v1/platform-operators/accounts` | 비슈퍼관리자 생성 |
| `PUT` | `/api/v1/platform-operators/accounts/{operatorId}/authority` | 역할·직접 권한 전체 교체 |
| `PUT` | `/api/v1/platform-operators/accounts/{operatorId}/suspension` | 계정 중지 |
| `GET` | `/api/v1/platform-operators/audit-events` | 감사 검색 |
| `GET` | `/api/v1/platform-operators/audit-events/{eventKey}` | 상세와 보정 이력 |
| `POST` | `/api/v1/platform-operators/audit-events/{eventKey}/corrections` | 보정 사건 추가 |

관리 명령과 보정은 `Idempotency-Key`, `X-Admin-Reauthentication`, case ID·version을 요구한다. 조회는 case ID·version과 `X-Admin-Reason-Code`를 요구한다. 별도 feature flag 없이 #275 flag를 쓴다.

## 오류 계약

| 상황 | HTTP | code |
| --- | --- | --- |
| 권한·배정·재인증·조회 범위 거부 | 403 | `ADMIN_001` |
| 현재 비밀번호 재인증 실패 | 401 | `ADMIN_002` |
| 슈퍼관리자 생성·변경·중지 또는 singleton 부재 | 409 | `ADMIN_003` |
| 금지 역할·직접 권한 | 400 | `ADMIN_004` |
| 이메일 충돌 | 409 | `ADMIN_005` |
| 감사 사건 없음 | 404 | `ADMIN_006` |
| 유효하지 않은 보정 | 409 | `ADMIN_007` |
| 민감정보·형식·구조화 사유 오류 | 400 | `COMMON_001` |
| 멱등 충돌 | 409 | `COMMON_007` |
| 변경 경합 | 409 | `COMMON_008` |
| 저장소·세션 회수·조회 감사 실패 | 503 | `COMMON_012` |

## migration

- `V43__create_platform_operator_management_audit.sql`로 고정한다. V41·V42와 기존 migration은 변경하지 않는다.
- V43은 `SUPER_ADMIN` 조건부 singleton unique index와 감사 테이블·검색 인덱스·불변 trigger를 추가한다. 관리 명령 멱등성은 V4 공통 `idempotency_commands` 원장을 재사용하고 별도 중복 원장을 만들지 않는다.
- ADMIN-009 공통 기간이 확정되기 전에는 자동 파기를 구현하지 않는다.

## 정확한 변경 allowlist

### 정본·OpenAPI

- `docs/02-users-and-permissions.md`
- `docs/05-functional-requirements.md`
- `docs/07-data-and-api-contracts.md`
- `docs/09-quality-operations-and-rules.md`
- `docs/service-policies/15-admin-operation.md`
- `docs/specs/README.md`
- `docs/specs/platform-operator-auth/spec.md`
- `docs/specs/platform-operator-authorization/spec.md`
- `docs/specs/platform-operator-openapi.yaml`
- `docs/specs/platform-operator-management-audit/spec.md`
- `docs/specs/platform-operator-management-audit/openapi.yaml`
- `redocly.yaml`

### Backend

- `backend/src/main/resources/db/migration/V43__create_platform_operator_management_audit.sql`
- `backend/src/main/java/com/miriyum/global/idempotency/IdempotencyCommand.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/management/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/audit/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/dto/management/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/dto/audit/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/entity/PlatformOperatorAuditEvent.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/{AdminCaseType,AdminCommandPurpose,AdminTargetType,PlatformOperatorAuditAction,PlatformOperatorAuditOutcome,PlatformOperatorAuditReason}.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/exception/AdminAuthorizationErrorCode.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/repository/{PlatformOperatorAccountRepository,PlatformOperatorAuthEventRepository,PlatformOperatorRoleGrantRepository,PlatformOperatorPermissionGrantRepository,PlatformOperatorReauthenticationApprovalRepository,PlatformOperatorAuditEventRepository}.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/service/{PlatformOperatorManagementService,PlatformOperatorManagementRequestFingerprint,PlatformOperatorSessionRevocationAfterCommit,PlatformOperatorAuditService,PlatformOperatorAuditWriter,OperatorAuthorityService,LastSuperAdminPolicy}.java`

### 테스트

- `backend/src/test/java/com/miriyum/domain/platformoperator/**`
- `backend/src/test/java/com/miriyum/global/idempotency/IdempotencyCommandTest.java`
- `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/ApiUrlConvention.java`
- `backend/src/test/java/com/miriyum/architecture/ApiUrlConventionTest.java`
- `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`
- `backend/build.gradle.kts`
- `backend/src/test/java/com/miriyum/testinfra/MySqlAuditTriggerExtension.java`
- `backend/src/test/resources/META-INF/services/org.junit.jupiter.api.extension.Extension`
- `backend/src/test/java/com/miriyum/domain/{notification/payment/store/reservation}/**/*MigrationTest.java` (V43 trigger migration compatibility only)

기존 migration, 다른 도메인의 Entity·Repository·Service·Controller, `SecurityConfig`, frontend, deploy와 `docs/superpowers/**`는 변경하지 않는다. allowlist 밖 변경이 필요하면 중단하고 spec을 다시 승인받는다.

## TDD 구현 순서

1. spec/OpenAPI·오류·migration 계약 테스트를 실패시킨다.
2. V43과 실제 MySQL singleton·UPDATE/DELETE trigger 테스트를 구현한다.
3. 계정 생성의 singleton·금지 grant·비밀 비노출 테스트와 구현을 추가한다.
4. 권한 교체·중지의 version·세션 회수·동시 경합 테스트와 구현을 추가한다.
5. append-only writer와 관리 명령 원자성 테스트를 추가한다.
6. 검색·상세·허용/거부 조회 감사와 보정 사건 테스트를 추가한다.
7. HTTP 권한·DTO·민감정보 비노출와 OpenAPI drift를 검증한다.
8. 전체 unit, MySQL·Valkey integration, build, Redocly와 allowlist 회귀를 실행한다.

## 인수 조건

- 일반 운영자·일반 사용자·매장 운영자는 운영자나 슈퍼관리자를 생성할 수 없다.
- 슈퍼관리자는 정확히 한 명이며 API로 생성·교체·중지·강등·핵심 권한 회수할 수 없다.
- 역할·권한 변경과 중지가 경합해도 최종 grant와 version이 하나로 수렴하고 구 세션이 거부된다.
- 임시 비밀번호, 토큰, 세션·승인 원문과 민감 비밀이 응답·로그·감사·DB에 없다.
- 실제 MySQL에서 감사 UPDATE·DELETE가 거부되고 보정 뒤 원 사건과 모든 보정이 함께 남는다.
- 검색·상세는 최소 권한·배정·사유를 요구하고 허용·거부 조회가 모두 감사된다.
- HTTP, OpenAPI와 전체 회귀가 성공하며 ADMIN-009 공통 보존기간을 구현하지 않는다.
