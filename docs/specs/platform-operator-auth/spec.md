# 기능 명세: 플랫폼 운영자 인증·세션·최초 비밀번호 변경

> 문서 상태: 구현 전 승인 기준
> 적용 단계: 고도화
> 소유 도메인: auth·platformoperator
> 관련 정책 ID: AUTH-006, AUTH-007, ADMIN-001, ADMIN-002, ADMIN-009, PRIV-011
> 소유 Issue: #275
> Parent: #269
> Blocks: #276~#282
> OpenAPI: `docs/specs/platform-operator-auth/openapi.yaml`
> Audience: `docs/specs/platform-operator-openapi.yaml`
> 최종 승인일: 2026-08-13

## 결과

공개 가입이 없는 플랫폼 운영자가 일반 사용자·매장 운영자와 물리적으로 분리된 인증 namespace로 로그인한다. 임시 비밀번호를 처음 변경하기 전에는 인증 관리 경로 외의 업무 API를 사용할 수 없으며, 변경 후에도 중앙 세션의 유휴·절대 만료, 단일 동시 세션, 계정 상태와 권한 버전을 모든 보호 요청에서 검증한다.

## 범위

### 포함

- 플랫폼 운영자 전용 계정·principal·JWT audience·쿠키·HTTP namespace
- 이메일·비밀번호 로그인, Refresh Token 회전, 로그아웃과 CSRF 준비
- 임시 비밀번호 최초 변경과 제한 세션
- Valkey 중앙 세션의 유휴 30분·절대 8시간 만료
- 계정당 활성 세션 1개와 새 로그인 시 이전 세션 회수
- 계정 중지, `authority_version` 또는 `session_version` 변경 시 즉시 세션 거부
- feature flag 기반 Controller·Security·OpenAPI audience 비노출
- 인증 보안 사건의 최소 append-only 기록

### 제외

- 공개 회원가입과 계정 생성 HTTP API
- 운영자 역할·세부 권한 catalog와 고위험 명령 재인증: #276
- 단일 슈퍼관리자의 비슈퍼관리자 계정 생성·정지·권한 관리 HTTP API와 통합 감사 조회: #282
- WebAuthn 등록·인증·분실 복구
- 일반 사용자·매장 운영자 Entity·Repository·Service·Controller 변경
- 프론트엔드 로그인 화면과 생성 클라이언트

## namespace와 분리 불변식

| 항목 | 플랫폼 운영자 계약 |
| --- | --- |
| HTTP audience | `/api/v1/platform-operators/**` |
| 인증 root | `/api/v1/platform-operators/auth` |
| 계정 테이블 | `platform_operator_accounts` |
| 기본 키 | `platform_operator_account_id` |
| principal subject | `platform-operator:{platform_operator_account_id}` |
| JWT audience·namespace | `platform-operator` |
| Refresh 쿠키 | `MIRIYUM_PLATFORM_OPERATOR_REFRESH` |
| CSRF 쿠키 | `MIRIYUM_PLATFORM_OPERATOR_XSRF_TOKEN` |
| 쿠키 Path | `/api/v1/platform-operators/auth` |

- 일반 사용자·매장 운영자 자격, 이메일 일치, 요청 본문의 역할 값으로 플랫폼 운영자 인증이나 권한을 만들지 않는다.
- 플랫폼 운영자 토큰은 consumer·store-operator API에서 거부되고, consumer·store-operator 토큰은 플랫폼 운영자 API에서 거부된다.
- 공개 `accounts` collection이나 가입 호환 경로를 만들지 않는다.

## 계정과 비밀번호 상태

`platform_operator_accounts`는 다음 중앙 상태를 소유한다.

- 계정 상태: `ACTIVE`, `SUSPENDED`
- 비밀번호 상태: `TEMPORARY`, `ACTIVE`
- 임시 비밀번호 만료 시각
- 임시 비밀번호 누적 실패 횟수
- 권한 버전 `authority_version`
- 세션 버전 `session_version`
- JPA 동시 변경 버전 `row_version`

임시 비밀번호의 유효기간과 실패 한도는 ADMIN-001이 중앙 설정을 요구하지만 정확한 수치를 확정하지 않았다. 구현은 임의 기본값을 만들지 않고 feature flag가 켜진 환경에 양수 설정을 필수로 요구한다. #282의 계정 발급은 같은 설정을 사용하여 만료 시각을 기록한다.

로그인은 이메일 존재 여부, 임시 비밀번호 상태, 실패 횟수나 계정 상태를 자격 증명 검증 전에 구분해 노출하지 않는다. 임시 비밀번호가 만료됐거나 실패 한도를 초과한 계정은 새 세션을 발급하지 않고 `AUTH_005`와 같은 공통 로그인 실패로 응답한다.

## 세션과 토큰 계약

- Access JWT의 최대 수명은 기존 AUTH-007과 같은 15분이다.
- 플랫폼 운영자 Refresh Token은 중앙 세션에 결속되며 30분 유휴 또는 로그인 시작부터 8시간 절대 만료 중 먼저 도달한 시점까지만 사용할 수 있다.
- Refresh 성공은 Refresh Token을 회전하고 최근 활동 시각과 유휴 만료를 갱신하지만 절대 만료를 연장하지 않는다.
- 보호 API의 유효한 요청도 최근 활동 시각과 유휴 만료를 원자적으로 갱신한다.
- 계정당 활성 세션은 하나다. 동시 로그인이 경합해도 최종 활성 세션 포인터와 유효 세션은 하나만 남고 이전 세션은 회수된다.
- Access JWT는 원문 목록을 저장하지 않지만 세션 식별자와 계정·권한·세션 버전을 포함한다. 보호 요청은 MySQL 중앙 계정 상태와 Valkey 세션을 다시 검증하므로 계정 중지나 버전 변경 뒤 기존 Access JWT도 즉시 거부된다.
- Valkey에는 Refresh Token과 세션 식별자의 안전한 해시, 회전 식별자, 계정 ID, 버전과 시각만 저장한다. 토큰 원문은 저장하지 않는다.
- Valkey 또는 중앙 세션 상태를 확인할 수 없으면 로그인·refresh·logout·보호 API를 `503 COMMON_012`로 실패 폐쇄한다.

## 최초 비밀번호 변경

1. 임시 자격 증명 로그인이 성공하면 `passwordChangeRequired=true`인 제한 세션을 발급한다.
2. 제한 세션은 CSRF 준비, refresh, logout, 최초 비밀번호 변경만 사용할 수 있다.
3. 다른 플랫폼 운영자 업무 API는 인증됐더라도 `403 AUTH_012`로 거부한다.
4. 최초 변경 요청은 현재 임시 비밀번호를 다시 확인하고 새 비밀번호와 확인 값에 AUTH-006 정책을 적용한다.
5. 계정의 비밀번호 상태와 `row_version`을 조건부로 변경하여 동시 요청 중 하나만 확정한다.
6. 성공 시 기존 제한 세션을 회수하고 `session_version`을 증가시킨 뒤 정상 세션과 새 Access/Refresh 쌍을 발급한다.
7. 비밀번호 해시, 요청 원문, Access/Refresh Token과 세션 식별자 원문은 로그·감사 사건에 남기지 않는다.

## API 계약

| Method | Path | 인증·검증 | 결과 |
| --- | --- | --- | --- |
| `POST` | `/api/v1/platform-operators/auth/sessions` | 이메일·비밀번호, 로그인 지연·임시 자격 상태 | 제한 또는 정상 세션 생성 |
| `POST` | `/api/v1/platform-operators/auth/token-refreshes` | Refresh 쿠키, JSON, Origin·Referer | 회전된 Access/Refresh 쌍 |
| `GET` | `/api/v1/platform-operators/auth/csrf-tokens/current` | IP 요청 제한 | CSRF 쿠키와 응답 토큰 |
| `DELETE` | `/api/v1/platform-operators/auth/sessions/current` | Refresh 쿠키, `X-CSRF-TOKEN` | 멱등 세션 회수와 쿠키 만료 |
| `PUT` | `/api/v1/platform-operators/auth/initial-password` | 제한 Access JWT, 현재·새 비밀번호 | 최초 변경과 정상 세션 재발급 |

로그인·refresh·최초 비밀번호 변경 성공 응답은 `accessToken`, `tokenType=Bearer`, `expiresIn=900`, `passwordChangeRequired`, `idleExpiresAt`, `absoluteExpiresAt`을 반환한다. Refresh Token은 응답 본문에 포함하지 않는다.

## 브라우저·CSRF 계약

- Access JWT는 JSON 응답과 shell 메모리, `Authorization: Bearer` 헤더만 사용한다.
- Refresh 쿠키는 `HttpOnly`, `Secure`, `SameSite=Lax`, host-only와 전용 Path를 사용한다.
- CSRF 쿠키는 JavaScript가 읽어 `X-CSRF-TOKEN`으로 되돌려 보낼 수 있지만 인증 자격으로 사용하지 않는다.
- refresh는 동일 Origin의 `POST application/json`과 `Origin`·`Referer` 검증을 요구한다.
- logout은 Refresh 쿠키와 CSRF cookie/header 일치를 검증한다. 검증 성공 뒤에는 중앙 폐기 결과와 무관하게 만료 쿠키를 응답에 싣는다.
- 로그인과 최초 비밀번호 변경은 자동 첨부 쿠키가 아니라 요청 본문·Bearer Access JWT로 인증하므로 별도 CSRF 토큰을 요구하지 않는다.

## 오류와 HTTP 상태

| 상황 | HTTP | code |
| --- | --- | --- |
| 자격 증명 불일치·임시 자격 만료/실패 한도 | 401 | `AUTH_005` |
| Access Token 누락·만료·오류 | 401 | `AUTH_001`~`AUTH_003` |
| token namespace 불일치 | 401 | `AUTH_004` |
| Refresh Token 누락·오류·회전 경쟁 패배 | 401 | `AUTH_007`·`AUTH_008` |
| 계정 중지 | 403 | `AUTH_011` |
| 최초 비밀번호 변경 전 업무 API | 403 | `AUTH_012` |
| 세션 유휴·절대 만료, 회수 또는 버전 불일치 | 401 | `AUTH_015` |
| CSRF·Origin 검증 실패 | 403 | `AUTH_009`·`AUTH_010` |
| 동시 최초 비밀번호 변경 충돌 | 409 | `COMMON_008` |
| 중앙 세션 상태 확인 불가 | 503 | `COMMON_012` |
| feature flag OFF 또는 공개 가입 경로 | 404 | `COMMON_005` MVC 계약 |

`AUTH_015`는 세션이 만료·회수됐는지, 버전이 바뀌었는지를 외부에 구분하지 않는 플랫폼 운영자 세션 무효 오류다.

## feature flag와 OpenAPI audience

- 설정 이름은 `miriyum.platform-operator.enabled`이며 기본값은 `false`다.
- OFF에서는 플랫폼 운영자 Controller와 활성 Security chain bean을 만들지 않는다.
- OFF 전용 chain은 `/api/v1/platform-operators/**`를 MVC까지 통과시켜 Controller 부재의 실제 404를 반환하게 한다.
- 저장소는 OpenAPI를 HTTP로 제공하지 않는다. 전용 정적 audience `platform-operator-openapi.yaml`은 MVP aggregate·consumer·store-operator audience와 분리한다.
- 배포·문서 게시 파이프라인이 나중에 OpenAPI HTTP 노출을 추가한다면 같은 flag를 입력으로 사용해야 하며, OFF 환경에서 audience를 게시하지 않는다.

## 데이터와 migration

- migration 번호는 열린 선행 PR의 V37·V38 예약을 고려해 `V39__create_platform_operator_auth.sql`로 고정한다.
- 구현 직전 최신 `dev`를 다시 fetch하여 V39 충돌 여부를 확인한다. 충돌하면 migration 파일을 작성하기 전에 새 번호를 승인받는다.
- `platform_operator_accounts.email`은 플랫폼 운영자 계정 유형 안에서 유일하다. consumer·store-operator 이메일과의 교차 유일성은 두지 않는다.
- 임시 비밀번호 누적 실패 횟수는 로그인 지연 상태와 별도로 MySQL에서 원자적으로 증가시키며 설정 한도에 도달하면 해당 임시 자격을 사용할 수 없다. 정상 최초 변경 뒤에는 이 값을 초기화한다.
- 인증 사건 테이블은 `platform_operator_auth_events`이며 최초 비밀번호 변경과 세션 회수 등 #275의 인증 사건만 append한다.
- 감사 사건에는 계정 ID, 사건 종류, 당시 권한·세션 버전, 결과와 발생 시각만 기록한다. 비밀번호, 토큰, 세션 ID 원문, 요청 본문과 자유 입력 사유는 기록하지 않는다.
- #282는 이 원장을 삭제·수정하지 않고 `AUTH:*` event key의 안전 projection으로 통합 감사 조회 계약에 연결한다.

## 보안 처리 흐름

feature ON에서는 다음 filter chain을 사용한다.

1. 로그인·refresh·logout·CSRF 준비 공개 인증 chain
2. 최초 비밀번호 변경과 향후 업무 API를 위한 플랫폼 운영자 보호 chain
3. 그 밖의 경로를 거부하는 기존 default chain

feature OFF에서는 플랫폼 운영자 전체 경로 전용 permit-to-MVC chain만 생성한다. 보호 chain의 인증 필터는 JWT 검증 뒤 MySQL 계정 상태·버전과 Valkey 세션을 확인하고, 제한 principal에는 최초 변경 전용 authority만 부여한다.

## 동시성·실패 처리

- 세션 생성·회전·touch·회수는 Valkey Lua 또는 동등한 단일 원자 연산으로 수행한다.
- 최초 비밀번호 변경은 MySQL `row_version` 조건부 변경과 인증 사건 기록을 한 트랜잭션에서 처리한다.
- MySQL 변경 성공 후 Valkey 새 정상 세션 발급이 실패하면 기존 제한 세션은 다시 유효하게 만들지 않는다. 사용자는 새 비밀번호로 다시 로그인한다.
- 로그아웃은 반복·경합에도 같은 종료 상태로 수렴한다.
- 비밀번호 변경과 계정·권한 버전 변경 후 세션 회수 실패는 성공으로 숨기지 않고 실패 폐쇄하며, 원문 식별자가 없는 운영 오류만 기록한다.

## 테스트와 검증

- Entity: 임시 비밀번호 상태, 최초 변경 1회, 계정 정지·버전 증가
- Service: 로그인·refresh·logout·최초 변경·제한 세션·민감정보 비기록
- HTTP/Security: namespace 교차 거부, 제한 업무 403, CSRF·Origin, 가입 404, flag OFF 전체 404
- Valkey 통합: 동시 로그인 1개, refresh 1회 회전, 유휴·절대 만료, 회수, 장애 실패 폐쇄
- 실제 MySQL Testcontainers: V39 제약, 최초 변경 경합, 계정·버전 조건부 변경과 사건 원자성
- OpenAPI: Controller path·method·status·DTO, audience 분할, MVP aggregate 비포함, 가입 경로 부재
- 전체 명령: `backend.test`, `backend.integration-test`, `backend.build`, OpenAPI lint/contract, `git diff --check`

시간 테스트는 주입된 `Clock`으로 수행하고 `sleep`에 의존하지 않는다. 동시성 테스트는 barrier/latch를 사용한다.

## 정확한 변경 파일 allowlist

### 정본·계약

- `docs/02-users-and-permissions.md`
- `docs/05-functional-requirements.md`
- `docs/07-data-and-api-contracts.md`
- `docs/09-quality-operations-and-rules.md`
- `docs/service-policies/01-member-auth.md`
- `docs/service-policies/15-admin-operation.md`
- `docs/specs/README.md`
- `docs/specs/platform-operator-auth/spec.md`
- `docs/specs/platform-operator-auth/openapi.yaml`
- `docs/specs/platform-operator-openapi.yaml`
- `redocly.yaml`

### Backend 공통 확장·설정·migration

- `backend/ai/implementation-guardrails.md`
- `backend/src/main/resources/application.yml`
- `backend/src/main/resources/db/migration/V39__create_platform_operator_auth.sql`
- `backend/src/main/java/com/miriyum/domain/auth/cookie/AuthCookieFactory.java`
- `backend/src/main/java/com/miriyum/domain/auth/exception/AuthErrorCode.java`
- `backend/src/main/java/com/miriyum/domain/auth/jwt/JwtTokenProvider.java`
- `backend/src/main/java/com/miriyum/domain/auth/jwt/ParsedToken.java`
- `backend/src/main/java/com/miriyum/domain/auth/jwt/SessionTokenClaims.java`
- `backend/src/main/java/com/miriyum/domain/auth/jwt/TokenNamespace.java`
- `backend/src/main/java/com/miriyum/domain/auth/ratelimit/RateLimitFilter.java`

### 새 플랫폼 운영자 구현

- `backend/src/main/java/com/miriyum/domain/platformoperator/config/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/controller/auth/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/dto/auth/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/entity/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/enums/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/repository/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/service/**`
- `backend/src/main/java/com/miriyum/domain/platformoperator/session/**`

### 테스트

- `backend/src/test/java/com/miriyum/architecture/AudienceOpenApiContractTest.java`
- `backend/src/test/java/com/miriyum/architecture/DomainPackageArchitectureTest.java`
- `backend/src/test/java/com/miriyum/architecture/HttpApiNamespaceContractTest.java`
- `backend/src/test/java/com/miriyum/domain/auth/jwt/JwtTokenProviderTest.java`
- `backend/src/test/java/com/miriyum/domain/auth/jwt/TokenNamespaceTest.java`
- `backend/src/test/java/com/miriyum/domain/auth/ratelimit/RateLimitFilterTest.java`
- `backend/src/test/java/com/miriyum/domain/platformoperator/**`

`SecurityConfig`, consumer/store-operator Entity·Repository·Service·Controller, 기존 migration과 frontend 파일은 변경하지 않는다. 구현 중 allowlist 밖의 파일이 필요하면 편집을 중단하고 범위를 다시 승인받는다.

## 인수 조건

- 다른 계정 유형의 자격으로 플랫폼 운영자 API를 사용할 수 없다.
- 공개 가입 endpoint가 없고 `/accounts` 요청은 404다.
- 최초 비밀번호 변경 전에는 인증 관리 외 플랫폼 운영자 업무 API가 403이다.
- 유휴 30분 또는 로그인 후 8시간이 지나면 refresh와 보호 API가 거부된다.
- 동시 로그인 후 활성 세션은 하나이며 이전 세션은 즉시 거부된다.
- 계정 중지나 권한·세션 버전 변경 뒤 기존 Access/Refresh Token이 모두 거부된다.
- feature flag OFF에서 플랫폼 운영자 Controller가 없고 namespace 요청이 404다.
- 비밀번호·토큰·세션 원문이 로그·감사 사건에 없다.
- HTTP 권한, 동시성, OpenAPI 드리프트와 실제 MySQL 통합 테스트가 성공한다.

## 후속 #276 경계

#276은 이 기능이 제공하는 `platform-operator` principal, `authority_version`, 정상 세션 guard와 세션 회수 계약 위에 역할·세부 권한, 사건 배정, 현재 비밀번호 재인증과 목적 결속 일회 승인을 추가한다. #276은 인증 namespace·세션 저장소·쿠키를 새로 만들거나 일반 사용자·매장 운영자 Auth를 재사용하지 않는다.
