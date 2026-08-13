# ADR-008: API URL 정본 문법과 실행 가능한 계약 검증

- 상태: Accepted
- 결정일: 2026-08-13
- 관련 Issue: [#303](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/303)
- 선행 결정: [#82](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/82)

## 배경

Issue #82는 공개 API와 일반 사용자·매장 운영자 API를 각각 `/api/v1/stores/**`, `/api/v1/consumers/**`, `/api/v1/store-operators/**`로 구분했다. 인증된 본인 범위에는 `/me`를 사용하고, 구 경로 alias 없이 Controller, Security, JWT cookie Path, 멱등성 fingerprint와 OpenAPI를 함께 전환했다.

그 뒤 `dev`에 2차 MVP와 고도화 API가 추가되면서 namespace는 유지됐지만 하위 경로 결정 방식이 갈렸다. 소비자 소유 업무 리소스가 `/consumers/me/**`와 `/consumers/**`에 나뉘었고, 상태 전이 생성이 `/cancellations`, `/fulfillments`, `/confirmations` 같은 복수 명사와 `/call`, `/arrive`, `/cancel`, `/publication` 같은 단수·동사 segment를 함께 사용한다. 기존 `HttpApiNamespaceContractTest`는 Controller의 class-level root prefix만 검사하므로 이 차이를 차단하지 못한다.

Issue #303은 #82를 대체하지 않는다. #82의 사용자 namespace와 `/me` 의미를 `dev`의 모든 현재 API에 적용하고, 하위 리소스 문법과 실행 가능한 검증 gate를 추가한다.

## 결정

### 호출자 namespace

- 인증된 일반 사용자 전용 API는 `/api/v1/consumers/**`에 둔다.
- 인증된 매장 운영자 전용 API는 `/api/v1/store-operators/**`에 둔다.
- 무인증 공개 API는 호출자 namespace를 두지 않고 `/api/v1/stores/**` 같은 리소스 중심 경로를 사용한다.
- Refresh·CSRF cookie 범위를 제한하기 위해 계정 유형별 인증 API의 `/auth` segment를 유지한다.
- `/me`는 계정 DTO만 뜻하지 않고 인증된 본인의 소유 범위를 뜻한다. 소비자 본인 소유 예약·픽업·결제·알림은 `/api/v1/consumers/me/**` 아래에 둔다.
- 매장 운영자 업무는 본인 계정 `/store-operators/me`와 관리 대상 `/store-operators/stores/{storeId}/**`를 분리한다.

### segment와 HTTP method

- 고정 segment는 lowercase `kebab-case`를 사용하고 식별자는 소유 리소스 바로 뒤에 둔다.
- 컬렉션과 `POST`로 생성하는 상태 전이·사건 리소스는 복수 명사를 사용한다.
- 조회는 `GET`, 전체 교체는 `PUT`, 부분 수정은 `PATCH`, 리소스·사건 생성은 `POST`, 실제 삭제 가능한 리소스 제거는 `DELETE`를 사용한다.
- 단일 속성·projection·계산 결과는 `contact`, `visibility`, `selling-status`, `end-at`, `menu-hold-availability`, `pickup-availability`, `deactivation-impact`처럼 승인된 단수 명사를 사용할 수 있다.
- 인증의 `me`, `current`, `auth`와 provider 식별자 `portone`은 구조·식별자 segment로 허용한다.
- 동작을 나타내는 `call`, `arrive`, `check-in`, `cancel`, `search`, `publication`, `retirement`를 endpoint의 마지막 명령 segment로 사용하지 않는다.

## 현재 경로 전환표

### 소비자 본인 소유 리소스

| 기존 경로 | 정본 경로 |
| --- | --- |
| `/api/v1/consumers/reservations/**` | `/api/v1/consumers/me/reservations/**` |
| `/api/v1/consumers/pickup-reservations/**` | `/api/v1/consumers/me/pickup-reservations/**` |
| `/api/v1/consumers/payments/**` | `/api/v1/consumers/me/payments/**` |
| `/api/v1/consumers/me/notifications` | 유지 |
| `/api/v1/consumers/me/reservations` | 유지하며 생성·목록·상세·취소를 같은 컬렉션으로 통합 |

### 상태 전이와 작업 리소스

| 기존 suffix 또는 경로 | 정본 suffix 또는 경로 |
| --- | --- |
| `/{id}/publication` | `/{id}/publications` |
| `/{id}/publication-cancellation` | `/{id}/publication-cancellations` |
| `/{id}/retirement` | `/{id}/retirements` |
| `/{id}/cancellation` | `/{id}/cancellations` |
| `/waiting-teams/{waitingTeamId}/call` | `/waiting-teams/{waitingTeamId}/calls` |
| `/waiting-teams/{waitingTeamId}/arrive` | `/waiting-teams/{waitingTeamId}/arrivals` |
| `/waiting-teams/{waitingTeamId}/check-in` | `/waiting-teams/{waitingTeamId}/check-ins` |
| `/waiting-teams/{waitingTeamId}/cancel` | `/waiting-teams/{waitingTeamId}/cancellations` |
| `/menus/{menuId}/alternatives/search` | `/menus/{menuId}/alternative-searches` |
| `/waiting-close-jobs/{jobId}` | `/waiting-closure-jobs/{jobId}` |
| `/waiting-settings/disable-impact` | `/waiting-settings/deactivation-impact` |

이미 복수 명사인 `/confirmations`, `/cancellations`, `/fulfillments`와 계정 인증의 `/accounts`, `/sessions`, `/token-refreshes`, `/csrf-tokens`는 유지한다. `/visibility`, `/selling-status`, `/end-at`, 공개 availability와 `/payments/webhooks/portone`도 단일 값·projection 또는 provider 진입점이므로 유지한다.

## 원자적 변경 경계

경로 문자열을 가진 소비자는 기능별로 다음 순서를 지키되 최종 PR에는 전부 포함한다.

1. Controller class/method mapping과 Controller 테스트
2. Spring Security matcher, JWT namespace, cookie Path, CSRF/Origin·Referer와 rate-limit 대상
3. HTTP route를 포함하는 멱등성 fingerprint와 고정 canonical 문자열 테스트
4. 기능별 OpenAPI path와 public·consumer·store-operator entrypoint `$ref`
5. OpenAPI 생성 TypeScript와 frontend API 호출부
6. 활성 `docs/06`, `docs/07`, `docs/09`, 관련 기능 spec과 handoff 문서
7. PR template의 API 계약 checklist

경로 변경이 payload, 오류 의미나 상태 전이를 바꾸지 않도록 각 기능의 기존 Controller·Security·service 회귀 테스트를 유지한다. 새 URL 외의 business code refactoring은 포함하지 않는다.

## 실행 가능한 거버넌스

### Spring route inventory

테스트 전용 `RequestMappingHandlerMapping` subclass가 보호된 `getMappingForMethod`를 노출한다. classpath에서 `@RestController`를 스캔하고 각 handler method를 Spring MVC의 실제 mapping 결합 규칙으로 해석한다. class-level과 method-level annotation, 여러 HTTP method와 path 조합을 `ApiRoute(httpMethod, normalizedPath)` 집합으로 만든다. Controller bean이나 외부 infrastructure를 시작하지 않으므로 conditional feature Controller도 누락하지 않는다.

정규화는 path variable 이름을 보존한다. OpenAPI와 Controller가 `{reservationId}`와 `{id}`처럼 서로 다른 계약 이름을 사용하면 차이로 보고한다. `/api/v1/**` 이외의 actuator·framework mapping은 비교 범위에서 제외한다.

### URL convention gate

`ApiUrlConventionTest`는 전체 Spring route inventory에 다음을 적용한다.

- controller package audience와 `/consumers`, `/store-operators`, 공개 namespace 일치
- `/me`와 `/auth`의 승인된 위치
- 고정 segment의 lowercase `kebab-case`
- 컬렉션·상태 전이 segment의 복수 명사 형태
- 승인된 단수 값·projection segment allowlist
- 구 namespace와 이번 전환표의 구 segment denylist

새 단수 segment가 필요하면 임의 예외를 추가하지 않는다. Issue와 OpenAPI에서 그것이 단일 값·projection인지 설명하고 convention test allowlist를 같은 PR에서 검토한다.

### Controller–OpenAPI 완전 대조

`ControllerOpenApiContractTest`는 모든 기능별 `docs/specs/*/openapi.yaml`에서 path item의 실제 HTTP operation을 수집한다. path가 없는 `mvp1-common/openapi.yaml`과 audience·aggregate entrypoint는 정본 operation 집합에서 제외한다. `$ref` entrypoint는 기존 `AudienceOpenApiContractTest`가 기능별 원본으로 정확히 연결되는지 계속 검증한다.

Spring route와 기능별 OpenAPI route의 집합 차이를 양방향으로 검사한다.

- Spring에만 있는 route: 문서화되지 않은 runtime API로 실패
- OpenAPI에만 있는 route: 구현되지 않은 계약으로 실패
- path는 같고 method가 다른 route: 양쪽 차집합으로 실패

현재 승인 미노출 목록의 payment와 webhook route는 consumer·public audience entrypoint에 각각 연결한다. `mvp1-openapi.yaml`은 1차 MVP aggregate이므로 이후 단계 route를 포함하지 않는 기존 단계 계약을 유지한다.

## 문서와 팀 작업 흐름

영구 URL 문법은 `docs/07-data-and-api-contracts.md`가 소유한다. `docs/06-system-architecture.md`는 OpenAPI 정본과 Spring mapping 검증 구조를, `docs/09-quality-operations-and-rules.md`는 convention 및 완전 대조 CI gate를 기록한다. 기능별 path는 해당 `docs/specs/<feature>/openapi.yaml`만 소유하고 다른 문서는 링크와 예시만 제공한다.

`.github/pull_request_template.md`에는 다음을 확인하는 API 변경 checklist를 추가한다.

- 기능별 OpenAPI를 먼저 변경했는가
- audience, `/me`, 복수 명사와 `kebab-case` 규칙을 지켰는가
- Security, cookie, rate limit과 fingerprint 소비자를 함께 변경했는가
- Controller–OpenAPI 및 구 URL 회귀 테스트를 실행했는가

문서만으로 준수를 선언하지 않는다. backend CI의 테스트가 신규·변경 API의 위반을 차단하는 실행 근거다.

## 오류와 보안 불변식

- 새 경로에서도 기존 HTTP status, 외부 오류 code, message, response envelope를 유지한다.
- consumer token은 `/api/v1/consumers/**`, store-operator token은 `/api/v1/store-operators/**`에서만 인증 주체가 된다.
- 교차 namespace token은 기존 `AUTH_004` 계약으로 거부한다.
- Refresh·CSRF cookie 이름과 보안 속성은 유지하고 승인된 `/auth` Path만 사용한다.
- 공개 route는 기존과 같이 principal을 필수로 요구하지 않는다.
- 멱등성 key의 actor·command·body 정규화는 유지하고 canonical route만 새 경로로 교체한다.
- 구 경로 재시도와 새 경로 재시도 사이의 fingerprint 호환은 제공하지 않는다.

## 호환성과 롤백

구 URL은 비즈니스 handler에 도달하거나 상태를 변경하지 않는다. fail-closed Security 처리 순서 때문에 정확한 응답이 401, 403 또는 404일 수 있으므로 호환성 회귀의 핵심은 구 경로의 2xx 성공과 상태 변경이 모두 없다는 것이다.

백엔드와 frontend API 소비자는 같은 릴리스 계약으로 전환한다. 혼합 버전은 지원하지 않는다. DB 변경이 없으므로 롤백 시 Issue #303의 code, OpenAPI와 frontend 변경을 하나의 단위로 revert한다. Controller만 되돌리거나 Security, cookie, fingerprint, OpenAPI 또는 frontend를 선택적으로 되돌리는 것은 혼합 계약을 만들기 때문에 허용하지 않는다.

## 검토한 대안

### 문서와 경로 정규식만 추가

구현 비용은 낮지만 Controller와 OpenAPI의 누락·초과를 검출하지 못하고 실제 Spring의 class/method mapping 결합 결과를 증명하지 못한다. 거절한다.

### 공용 Java URL 상수 또는 중앙 route registry

Java 문자열 중복은 줄지만 도메인 Controller가 중앙 클래스에 결합되고 OpenAPI와 frontend까지 단일화하지 못한다. URL 사실의 정본이 Java와 OpenAPI로 다시 나뉘므로 거절한다.

### OpenAPI 정본과 Spring route 완전 대조

기능별 OpenAPI의 기존 소유권을 유지하면서 runtime route drift와 명명 규칙을 CI에서 함께 차단한다. 이 방식을 채택한다.

## 검증 전략

구현은 다음 TDD 순서를 따른다.

1. Spring route inventory와 convention test를 작성해 현재 동사형·단수 명령·`/me` 분산 때문에 실패하는지 확인한다.
2. 기능군별 Controller/OpenAPI 기대 테스트를 새 경로로 바꿔 실패를 확인한다.
3. Controller, Security와 fingerprint를 최소 변경해 관련 backend test를 통과시킨다.
4. 기능별 OpenAPI와 audience entrypoint를 바꾸고 완전 대조 test를 통과시킨다.
5. 생성 TypeScript와 frontend 호출 테스트를 새 경로로 바꿔 실패를 확인한 뒤 호출부를 전환한다.
6. 구 URL이 2xx나 상태 변경을 만들지 않는 회귀를 고정한다.
7. 활성 문서와 PR template을 갱신한다.

최종 검증은 저장소 command registry와 실제 workflow에 있는 명령만 사용한다.

- backend fast unit·slice test
- 관련 backend integration shard와 전체 integration gate
- OpenAPI lint·bundle·generated client drift 검증
- frontend typecheck, test와 build
- 구 URL 및 구 canonical fingerprint 문자열 잔존 검색
- `git diff --check`
- GitHub Actions required `backend-ci`와 관련 frontend/OpenAPI check

조합된 cross-end API smoke command는 현재 `NOT CONFIGURED`이므로 실행하지 않은 상태를 성공으로 보고하지 않는다. frontend와 backend 개별 검증 및 Controller–OpenAPI 집합 대조를 현재 가능한 계약 증거로 사용한다.

## 완료 조건

- `dev`에 존재하는 모든 `/api/v1/**` Controller route가 승인된 문법을 만족한다.
- 기능별 OpenAPI와 Spring Controller의 `HTTP method + normalized path` 차집합이 양방향 0건이다.
- production, test, 기능별 OpenAPI, audience entrypoint, 생성 client, frontend 호출부와 활성 문서에 구 URL이 남지 않는다.
- Security, JWT cookie Path, rate limit과 멱등성 fingerprint가 정본 경로와 일치한다.
- 새 API의 문법 위반과 OpenAPI 누락이 backend CI에서 재현 가능하게 실패한다.
- 기존 business, 권한, 오류와 멱등성 회귀가 통과한다.
- 활성 API·아키텍처·품질 문서와 PR template이 같은 작업 절차를 안내한다.

## 관련 문서

- [시스템 아키텍처](../06-system-architecture.md)
- [데이터 및 API 계약](../07-data-and-api-contracts.md)
- [품질·운영 및 규칙](../09-quality-operations-and-rules.md)
- [루트 통합 계약](../../ai/integration-contracts.md)
