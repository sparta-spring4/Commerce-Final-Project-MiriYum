# Issue #82 API Contract and Cycle Remediation Design

> 작업 산출물 상태: 이 문서는 Issue #82 2단계 구현을 위한 설계 기록이며 활성 제품·아키텍처 정본이 아니다. 현재 계약은 GitHub Issue #82, `docs/06-system-architecture.md`, `docs/07-data-and-api-contracts.md`, `docs/09-quality-operations-and-rules.md`와 각 기능의 `docs/specs/*/openapi.yaml`이 소유한다.

## 1. 목표

Issue #82의 2단계로 사용자 유형별 HTTP namespace를 일관되게 바꾸고, URL에 종속된 Security·JWT·쿠키·멱등성·OpenAPI 계약을 원자적으로 정렬한다. 동시에 1단계에서 임시 baseline으로 남긴 `consumer ↔ reservation`, `menuhold ↔ reservation` 순환 의존을 제거하고, 공통 Store→Menu 거래 진입점을 `MenuTransactionFacade`로 명확히 한다.

완료 상태에서는 다음이 성립해야 한다.

- 일반 사용자 전용 API는 `/api/v1/consumers/**` 아래에만 있다.
- 매장 운영자 전용 API는 `/api/v1/store-operators/**` 아래에만 있다.
- `/api/v1/stores/**`를 포함한 공개 API는 기존 경로를 유지한다.
- 이전 consumer·store-operator URL은 매핑하지 않는다.
- 소비자 토큰과 매장 운영자 토큰의 교차 사용은 기존 `AUTH_004` 계약으로 거부한다.
- 구조 테스트의 순환 baseline은 빈 집합이다.
- 공개·일반 사용자·매장 운영자 OpenAPI 진입점과 전체 MVP1 진입점이 같은 기능별 원본을 참조한다.

## 2. 범위

### 2.1 포함

- Consumer, StoreOperator, Store, Schedule, Menu, Reservation, MenuHold, Pickup의 Controller URL
- Spring Security matcher와 JWT 요청 namespace 판정
- Refresh·CSRF 쿠키 Path
- HTTP 경로가 포함된 멱등성 fingerprint
- 기능별 OpenAPI 원본과 소비자 유형별 OpenAPI 진입점
- 예약 내역 HTTP 경계의 Reservation 도메인 이동
- Reservation 소유 `ReservationMenuHoldPort`와 MenuHold adapter
- MenuHold가 사용하는 좁은 `ReservationTimeResolutionService`
- `MenuTransactionService`의 `MenuTransactionFacade` 승격과 기존 소비자 전환
- Controller·Security·쿠키·fingerprint·OpenAPI·구조·통합 회귀 테스트
- 변경된 사실을 소유하는 활성 아키텍처·API·품질 문서

### 2.2 제외

- DB schema와 Flyway migration
- 응답 JSON 형상, 상태 전이, 권한 정책과 오류 코드 변경
- 기존 URL 호환 Controller, redirect 또는 이중 매핑
- 프론트엔드 구현과 generated client의 수동 편집
- 추천 도메인 또는 다른 MVP 단계 기능
- 순환 제거와 무관한 도메인 리팩터링

## 3. URL 계약

Controller의 기존 기능과 응답은 보존하고 base path만 다음 규칙으로 교체한다.

| 소비자 | 이전 패턴 | 새 패턴 |
|---|---|---|
| 일반 사용자 인증 | `/api/v1/consumer-auth/**` | `/api/v1/consumers/auth/**` |
| 일반 사용자 본인 정보 | `/api/v1/consumer-accounts/me/**` | `/api/v1/consumers/me/**` |
| 일반 사용자 예약 | `/api/v1/reservations/**` | `/api/v1/consumers/reservations/**` |
| 일반 사용자 픽업 | `/api/v1/pickup-reservations/**` | `/api/v1/consumers/pickup-reservations/**` |
| 매장 운영자 인증 | `/api/v1/store-operator-auth/**` | `/api/v1/store-operators/auth/**` |
| 매장 운영자 본인 정보 | `/api/v1/store-operator-accounts/me/**` | `/api/v1/store-operators/me/**` |
| 매장 운영 API | `/api/v1/store-operator/stores/**` | `/api/v1/store-operators/stores/**` |

`/api/v1/stores/**`, `/api/v1/store-categories`, `/api/v1/menu-categories`, `/api/v1/store-tags` 등 공개 계약은 바꾸지 않는다. Spring의 실제 Controller mapping을 수집하는 계약 테스트로 사용자 전용 경로가 새 namespace 밖에 남지 않음을 검증한다.

## 4. Security, JWT와 쿠키

Security chain은 공개, consumer, store-operator 경계를 새 경로로 매칭한다. Consumer JWT는 `/api/v1/consumers/**`, StoreOperator JWT는 `/api/v1/store-operators/**`에서만 인증 주체가 될 수 있다. 반대 namespace 토큰을 사용하면 기존 `TOKEN_NAMESPACE_MISMATCH`/`AUTH_004` 동작을 보존한다.

`TokenNamespace`의 쿠키 Path는 다음으로 바꾼다.

- Consumer Refresh·CSRF: `/api/v1/consumers/auth`
- StoreOperator Refresh·CSRF: `/api/v1/store-operators/auth`

쿠키 이름, `HttpOnly`, `Secure`, `SameSite`, 수명과 CSRF 비교 방식은 변경하지 않는다. 로그인, 재발급, CSRF 준비, 로그아웃과 만료 쿠키가 모두 동일한 새 Path를 사용해야 한다.

## 5. 멱등성 fingerprint

HTTP method와 route가 canonical input에 들어가는 모든 fingerprint를 새 URL로 바꾼다. 대상은 계정 수정·연락처, 매장 생성·수정, 메뉴 관리, 일정·휴무·예약 시간 정책, 일반 예약 생성·취소, 운영자 예약 처리, 픽업과 재고 관리다.

업무 command type, principal namespace, actor ID, body 정규화와 replay 동작은 유지한다. 테스트는 새 canonical 문자열을 고정하고 이전 URL 문자열이 production 및 test source에 잔존하지 않는지 검사한다.

## 6. 도메인 의존 방향

### 6.1 Consumer와 Reservation

`ConsumerAccountController`에서 예약 내역 method와 Reservation import를 제거한다. `/api/v1/consumers/me/reservations` HTTP 구현은 `reservation.controller.consumer`가 소유한다. 이 Controller는 기존 C-013 판정 순서를 보존하기 위해 Consumer의 공개 account service로 계정 활성 상태를 먼저 검증한 뒤, 인증된 account ID를 예약 이력 조회 계약에 전달한다.

그 결과 `consumer → reservation`은 제거되고 필요한 협력은 `reservation → consumer` 단방향으로 남는다. Consumer 계정 Controller는 본인 정보 조회·수정과 연락처 등록만 담당한다.

### 6.2 Reservation과 MenuHold

Reservation 도메인은 예약 생성·취소·이행에 필요한 최소 연산을 표현하는 `ReservationMenuHoldPort`와 입력·출력 계약을 소유한다. Reservation 서비스는 자기 도메인의 Port만 의존한다.

MenuHold 도메인의 adapter가 이 Port를 구현하고 기존 MenuHold 생성·해제·완료·snapshot 조회 서비스를 호출한다. MenuHold entity, repository, service와 DTO는 adapter 밖으로 노출하지 않는다. Reservation 응답에 필요한 메뉴 snapshot은 Reservation 소유 계약으로 변환한다.

MenuHold의 공개 가용량 조회는 거대한 `ReservationService` 대신 예약 시간 판정만 제공하는 `ReservationTimeResolutionService`와 그 전용 DTO를 사용한다. 이 서비스는 Reservation이 소유하고 MenuHold가 호출하므로 최종 compile-time 방향은 `menuhold → reservation` 하나만 남는다.

### 6.3 구조 gate

`DomainPackageArchitectureTest`에서 `consumer/reservation`, `menuhold/reservation`의 legacy cyclic pair와 edge baseline을 제거한다. 전체 도메인 그래프의 cyclic pair와 cyclic edge 기대값은 모두 빈 집합이어야 한다. 다른 도메인 Entity·Repository 직접 참조 금지와 공허한 검사 방지 규칙은 유지한다.

## 7. Menu 거래 Facade

기존 `MenuTransactionService`의 Store 거래 가능성 검증, Store→Menu 잠금 순서, caller transaction 요구와 rollback 의미를 보존한 채 `MenuTransactionFacade`로 승격한다. 별도 pass-through 타입은 만들지 않는다.

MenuHold runtime, MenuInventory 관리와 Pickup 생성 흐름은 이 Facade를 직접 사용한다. 기존 단위·통합 테스트도 실제 `MenuTransactionFacade` bean을 검증하도록 이름과 주입 대상을 바꾸되 MySQL 잠금 계약과 transaction 경계는 그대로 유지한다.

## 8. OpenAPI 구조

기능별 파일을 요청·응답·오류·operation 정의의 단일 원본으로 유지한다.

- `docs/specs/auth-account/openapi.yaml`
- `docs/specs/store-search/openapi.yaml`
- `docs/specs/reservation/openapi.yaml`
- `docs/specs/menu-hold-pickup/openapi.yaml`
- `docs/specs/mvp1-common/openapi.yaml`

다음 소비자별 진입점을 추가한다.

- `docs/specs/public-openapi.yaml`
- `docs/specs/consumer-openapi.yaml`
- `docs/specs/store-operator-openapi.yaml`

각 진입점은 자신의 경로만 기능별 원본에서 `$ref`하고 schema나 response를 복제하지 않는다. `docs/specs/mvp1-openapi.yaml`은 세 소비자별 진입점의 모든 path를 참조하는 전체 진입점으로 유지한다.

계약 검사는 다음을 보장한다.

- public 진입점에는 공개 경로만 있다.
- consumer 진입점의 전용 경로는 `/api/v1/consumers/**`다.
- store-operator 진입점의 전용 경로는 `/api/v1/store-operators/**`다.
- 소비자별 경로 집합의 합은 전체 MVP1 경로 집합과 같다.
- path 중복, 누락, 깨진 `$ref`와 구 URL이 없다.
- OpenAPI lint와 generated API 검증이 통과한다.

## 9. 오류 처리와 호환성

구 URL을 위한 호환 endpoint는 만들지 않는다. 구 경로는 Controller mapping과 Security 허용 목록에서 제거하며 테스트에서 더 이상 성공하지 않음을 확인한다. 비호환 URL 변경은 Issue #82가 승인한 계약 교체다.

새 URL에서 validation, 인증, 인가, not-found, conflict, idempotency와 service-unavailable 응답의 상태·오류 코드·공통 envelope는 기존 동작을 보존한다. Port/Adapter 변환은 기존 MenuHold 오류를 삼키거나 새 성공 응답으로 바꾸지 않는다.

## 10. 구현과 검증 전략

각 동작 변경은 TDD로 진행한다.

1. 새 URL 기대와 구 URL 거부 테스트를 먼저 실패시킨 뒤 Controller mapping을 바꾼다.
2. 교차 namespace와 쿠키 Path 테스트를 실패시킨 뒤 Security·JWT·쿠키를 바꾼다.
3. fingerprint 기대값을 실패시킨 뒤 canonical route를 바꾼다.
4. 소비자별 OpenAPI 경로 집합 테스트를 실패시킨 뒤 기능별 원본과 진입점을 바꾼다.
5. Reservation 소유 Port 계약 테스트를 만든 뒤 MenuHold adapter로 기존 협력을 연결한다.
6. 예약 내역 Controller 소유권 테스트와 구조 테스트를 실패시킨 뒤 Consumer 의존을 제거한다.
7. Facade 이름·거래 계약 테스트를 먼저 바꾼 뒤 production 타입과 소비자를 전환한다.
8. 관련 단위·통합 테스트, 전체 backend test, OpenAPI lint, generated API diff와 `git diff --check`를 실행한다.

최종 필수 증거는 Issue #82의 검증 계획과 활성 품질 gate가 정한 실제 명령 결과로 남긴다. Docker가 필요한 통합 테스트를 실행할 수 없다면 성공으로 간주하지 않고 차단 원인을 명시하며 GitHub Actions 결과를 별도 증거로 확인한다.

## 11. 위험과 완화

- URL 누락: 전체 Controller mapping과 OpenAPI path 집합을 비교하는 테스트로 방지한다.
- Security chain 공백 또는 중복: 사용자 유형별 정상·교차·미인증 요청 테스트로 방지한다.
- cookie Path 불일치: 생성·갱신·만료 쿠키를 모두 같은 namespace 계약으로 검사한다.
- fingerprint drift: route별 고정 canonical 테스트와 구 URL 검색으로 방지한다.
- Port가 과도하게 커지는 위험: Reservation 유스케이스가 실제 사용하는 연산과 값만 소유 계약으로 둔다.
- adapter의 transaction 변화: 기존 Reservation 및 MenuHold integration test로 잠금·rollback·실패 전파를 검증한다.
- OpenAPI 중복: 기능별 원본만 상세 정의를 소유하고 소비자별 파일은 `$ref` 전용으로 제한한다.

## 12. 롤백

DB 변경이 없으므로 코드와 계약 파일을 함께 revert하면 이전 URL과 의존 구조로 돌아간다. Controller만 되돌리거나 OpenAPI·Security·쿠키·fingerprint 일부만 선택적으로 되돌리는 것은 계약 불일치를 만들기 때문에 허용하지 않는다.
