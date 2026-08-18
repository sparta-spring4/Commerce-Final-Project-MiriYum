# 기능 명세: 현재 플랫폼 운영자 capabilities 조회

> 문서 상태: 구현 승인 기준
> 적용 단계: 고도화
> 소유 도메인: platformoperator
> 관련 정책 ID: ADMIN-001, ADMIN-002
> 소유 Issue: #403
> 선행 계약: #275, #276, #282
> 소비 Issue: #405
> OpenAPI: `docs/specs/platform-operator-capabilities/openapi.yaml`
> Audience: `docs/specs/platform-operator-openapi.yaml`
> 최종 승인일: 2026-08-18

## 결과와 경계

로그인한 플랫폼 운영자가 현재 중앙 권한 version의 활성 역할과 최종 유효 세부 권한을 조회한다. 플랫폼 운영자 콘솔은 이 응답만으로 메뉴·기능 노출을 판단하며 Access JWT를 디코딩하거나 역할→권한 표를 복제하지 않는다.

다른 운영자 조회·역할·권한 변경·프론트엔드 구현과 권한 catalog 변경은 제외한다. 이 기능은 #275의 인증·세션과 feature flag, #276의 역할·직접 권한 원장과 권한 version 계약을 그대로 사용한다.

## 권한 해석

- `GET /api/v1/platform-operators/me`는 요청 본문이나 query에서 운영자 ID·역할·권한을 받지 않고 인증 principal의 계정만 읽는다.
- `OperatorAuthorityReader.requireCurrentAuthority`가 principal의 `authorityVersion`과 현재 MySQL version을 다시 대조한다.
- 역할 목록은 현재 `platform_operator_role_grants`에 부여된 역할만 포함한다.
- 권한 목록은 각 역할의 `PlatformOperatorRole.permissions()`와 `platform_operator_permission_grants`의 직접 권한을 합친 집합이다.
- 역할과 권한은 enum 이름 오름차순으로 반환하고 중복을 제거한다.
- `SUPER_ADMIN`도 실제 역할 mapping이 제공하는 권한만 포함한다. 다른 역할의 권한이나 전체 catalog를 자동으로 추가하지 않는다.
- 기존 `PlatformOperatorRole`과 `PlatformOperatorPermission` catalog를 변경하지 않는다.

## 응답과 개인정보

응답 `data`는 다음 세 필드만 포함한다.

- `authorityVersion`: 현재 중앙 권한 version
- `roles`: 현재 활성 역할 목록
- `permissions`: 역할 권한과 직접 권한을 합친 최종 유효 권한 목록

운영자 ID, 이메일, 표시 이름, 계정 상태, 비밀번호 상태, 비밀번호·토큰·세션·재인증 승인 원문과 다른 개인정보는 포함하지 않는다. 최초 비밀번호 변경 필요 여부는 로그인 응답이 소유하며 제한 세션은 이 API 자체에 접근할 수 없다.

## 인증·오류 계약

| 상황 | HTTP | code |
| --- | --- | --- |
| Access Token 누락·만료·오류 | 401 | `AUTH_001`~`AUTH_003` |
| token namespace 불일치 | 401 | `AUTH_004` |
| 세션 만료·회수 또는 권한 version 불일치 | 401 | `AUTH_015` |
| 계정 중지 | 403 | `AUTH_011` |
| 최초 비밀번호 변경 전 제한 세션 | 403 | `AUTH_012` |
| 중앙 권한·세션 저장소 장애 | 503 | `COMMON_012` |
| feature flag OFF | 404 | MVC 404 |

새 오류 코드를 추가하지 않는다. #275 보호 chain이 token namespace, 계정 상태, 비밀번호 상태, 중앙 세션과 version을 먼저 검증하고 Service가 현재 권한 version을 다시 확인한다.

## feature flag와 OpenAPI audience

- 별도 flag를 만들지 않고 `miriyum.platform-operator.enabled`를 사용한다.
- ON일 때만 capabilities Controller와 Service bean을 등록한다.
- OFF에서는 플랫폼 운영자 비활성 Security chain이 요청을 MVC까지 통과시키고 Controller 부재로 실제 404를 반환한다.
- 기능 OpenAPI는 `platform-operator-capabilities/openapi.yaml`이 소유하고 `platform-operator-openapi.yaml`만 단일 `$ref`로 노출한다.
- public·consumer·store-operator audience와 `mvp1-openapi.yaml`에는 포함하지 않는다.
- 저장소는 OpenAPI를 HTTP로 제공하지 않는다. 후속 게시 파이프라인은 #275와 같은 flag가 OFF인 환경에서 platform-operator audience를 게시하지 않는다.

## 정확한 변경 allowlist

- `docs/05-functional-requirements.md`
- `docs/07-data-and-api-contracts.md`
- `docs/09-quality-operations-and-rules.md`
- `docs/service-policies/15-admin-operation.md`
- `docs/specs/README.md`
- `docs/specs/platform-operator-capabilities/{spec.md,openapi.yaml}`
- `docs/specs/platform-operator-management-audit/{spec.md,openapi.yaml}`
- `docs/specs/platform-operator-openapi.yaml`
- `redocly.yaml`
- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/authorization/PlatformOperatorCapabilitiesController.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/dto/authorization/PlatformOperatorCapabilitiesData.java`
- `backend/src/main/java/com/miriyum/domain/platformoperator/service/PlatformOperatorCapabilitiesService.java`
- `/me` 중복 소유권 제거에 필요한 기존 management Controller·Service·DTO
- `backend/src/test/java/com/miriyum/domain/platformoperator/**`
- `backend/src/test/java/com/miriyum/architecture/{AudienceOpenApiContractTest,ControllerOpenApiContractTest,HttpApiNamespaceContractTest}.java`

기존 권한 enum·role mapping·migration, 다른 도메인, frontend와 배포 설정은 변경하지 않는다.

## 테스트와 인수 조건

- unit: 중앙 snapshot 사용, 정렬, 역할 권한+직접 권한 합산과 `SUPER_ADMIN` 비확장
- Controller: 정확히 세 응답 필드, 인증·세션·개인정보 원문 비노출, flag OFF bean 부재
- 실제 MySQL·Valkey HTTP: 역할 조합, 직접 권한, 권한 회수 뒤 구 세션 401과 재로그인 결과, 중지 403, 무인증 401, 최초 비밀번호 403
- feature flag: OFF Controller 부재와 `/me` 404
- OpenAPI: 기능 단일 path·schema·오류, runtime mapping drift, 전용 audience 단일 `$ref`, 다른 audience 비포함

로컬에서는 관련 unit test와 해당 integration test class만 실행한다. 전체 backend suite는 GitHub CI를 권위 있는 검증 결과로 사용한다.

프론트 #405는 이 응답의 `roles`·`permissions`를 직접 소비하고 JWT 디코딩이나 역할→권한 표 복제를 하지 않는다.
