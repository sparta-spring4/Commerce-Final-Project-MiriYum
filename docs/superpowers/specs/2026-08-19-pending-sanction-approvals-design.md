# 추가 승인 대기 제재 조회 설계

## 목적

#278의 영구 정지 추가 승인 명령은 `sanctionId`와 현재 사건 version을 요구하지만, 제안자와 다른 승인자가 그 값을 안전하게 찾을 조회 경로가 없다. #425는 기존 제재 원장과 승인 명령을 변경하지 않고, 현재 운영자가 실제로 승인 후보로 검토할 수 있는 추가 승인 대기 제재만 최소 정보로 조회하게 한다.

## 선택한 접근

전용 읽기 projection `GET /api/v1/platform-operators/member-sanctions/pending-additional-approvals`를 추가한다. 범용 제재 검색은 필요 이상의 상태·필터·개인정보 노출을 만들고, 기존 회원지원 사건 목록 확장은 승인 대상과 일반 사건을 섞으므로 사용하지 않는다.

조회는 #278이 소유한 `MemberSanction` 원장과 repository만 사용한다. 회원 Entity·Repository나 다른 도메인 내부 구현은 참조하지 않는다. 기존 추가 승인 명령, 제재 정책, 상태 전이는 변경하지 않는다.

## HTTP 계약

- query: 0 기반 `page` 기본 0, `size` 기본 20, 허용 범위 1~100
- 정렬: `proposedAt DESC`, 동률은 내부 `id DESC`
- 대상: `PENDING_ADDITIONAL_APPROVAL` 상태이면서 `proposedByOperatorId`가 현재 운영자와 다른 제재
- 응답 항목: `sanctionId`, `version`, `accountType`, `accountId`, `reasonCode`, `policyVersion`, `proposedAt`
- `version`은 기존 추가 승인 POST가 `If-Match`로 받는 현재 member-support case row version이다.
- page metadata는 공통 `PageMetadata`를 그대로 사용한다.

Endpoint 자체가 영구 정지 추가 승인 대기 projection이므로 `level`과 `status`는 중복 노출하지 않는다. 제안자 ID, 이메일, 연락처, 자유형 제재 사유 원문, 제한 기능, 감사·세션 정보도 반환하지 않는다. `reasonCode`는 기존 원장에 저장된 제한된 분류 코드만 반환한다.

## 권한과 운영자 분리

Service는 repository를 호출하기 전에 `OperatorAuthorityReader.requireCurrentAuthority`로 현재 DB snapshot을 읽고 `ACCOUNT_PERMANENT_SANCTION_APPROVE` permission과 `SUPER_ADMIN` role을 함께 검사한다. 따라서 principal의 authority version과 현재 MySQL version이 일치해야 하며, 직접 permission만 가진 비-`SUPER_ADMIN`에게 실제 승인할 수 없는 항목을 노출하지 않는다.

현재 운영자의 자기 제안은 repository query에서 제외한다. 목록 결과에 `approvable` 같은 파생 상태를 추가하지 않는다. 기존 승인 명령의 `제안자와 다른 SUPER_ADMIN` 조건과 별도 재인증은 그대로 유지된다.

#275 인증 filter가 플랫폼 운영자 token namespace, 활성 계정, 중앙 세션, session version을 먼저 검증한다. 권한 version이 stale이면 조회도 `401 AUTH_015`, 권한이 없으면 repository 접근 전에 `403 ADMIN_001`이다.

## 활성화와 OpenAPI 노출

Controller는 기존 `PlatformOperatorMemberSupportController`에 추가하므로 `miriyum.platform-operator.enabled=true`와 `miriyum.member-support.enabled=true`가 모두 참일 때만 생성된다. 어느 하나라도 OFF이면 실제 MVC 404다.

정적 OpenAPI는 기능 원본 `docs/specs/member-support/openapi.yaml`과 전용 audience `docs/specs/platform-operator-openapi.yaml`에만 추가한다. public, consumer, store-operator, MVP aggregate에는 포함하지 않는다. 저장소는 runtime OpenAPI 문서를 제공하지 않으므로 flag별 정적 문서 변형은 만들지 않는다.

## 테스트 전략

- unit: permission·`SUPER_ADMIN` 선검사, stale authority 전달, 자기 제안 제외 query 인자, 페이지 경계, 응답 version과 최소 필드 mapping
- HTTP/MySQL integration: 실제 MySQL에서 상태·제안자 필터, `proposedAt DESC, id DESC`, 페이지 경계, 중앙 세션과 authority version, 권한 거부를 검증
- feature flag integration: platform operator OFF에서 Controller bean 부재와 GET 404
- contract: 기능 OpenAPI, audience 단일 `$ref`, 런타임 GET operation, 최소 schema, 다른 audience 비노출을 검증
- local verification: 가장 작은 관련 unit class와 `PlatformOperatorMemberSupportHttpIT`, `PlatformOperatorFeatureFlagIT`만 실행한다. 전체 `build`, `check`, `integrationTest`, integration A~D는 실행하지 않는다.

## 정확한 파일 allowlist

수정:

- `docs/specs/member-support/spec.md`
- `docs/specs/member-support/openapi.yaml`
- `docs/specs/platform-operator-openapi.yaml`
- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/membersupport/PlatformOperatorMemberSupportController.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/dto/membersupport/MemberSupportResponses.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/repository/membersupport/MemberSanctionRepository.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/PlatformOperatorMemberSupportHttpIT.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/config/PlatformOperatorFeatureFlagIT.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/PlatformOperatorOpenApiContractTest.java`

추가:

- `backend/src/main/java/com/miriyum/domain/platformoperator/service/membersupport/PendingMemberSanctionQueryService.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/membersupport/PendingMemberSanctionQueryServiceTest.java`
- `docs/superpowers/specs/2026-08-19-pending-sanction-approvals-design.md`
- `docs/superpowers/plans/2026-08-19-pending-sanction-approvals.md`

allowlist 밖의 파일이 필요하면 구현을 멈추고 활성 spec과 설계를 먼저 갱신한다.
