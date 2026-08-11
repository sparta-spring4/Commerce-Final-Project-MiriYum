# Issue #82 도메인 패키지 및 사용자 유형별 API 경로 재구성 설계

## 1. 배경과 목표

현재 백엔드는 `com.miriyum.domain.*` 아래에서 3레이어 아키텍처를 사용하지만, 매장과 관련된 서로 다른 책임이 `store` 하위에 집중되어 있고 HTTP 경로의 사용자 유형 표현도 일관되지 않다. 이 구조는 기능이 늘어날수록 패키지 소유권, 도메인 간 의존 방향, 공개 API와 인증 API의 경계를 파악하기 어렵게 만든다.

Issue #82의 목표는 다음 두 가지다.

1. 매장에 집중된 패키지를 실제 업무 도메인 단위로 분리한다.
2. 인증이 필요한 API 경로를 사용자 유형별 namespace로 일관되게 정리한다.

이번 작업은 기존 3레이어 아키텍처를 교체하는 작업이 아니다. `Controller → Service → Repository` 흐름과 `com.miriyum.domain.<domain>` 구조를 유지하면서, 도메인 소유권과 HTTP 경계를 명확하게 만드는 리팩터링이다.

## 2. 설계 원칙

### 2.1 도메인이 최상위 분리 기준이다

최상위 패키지를 `api/**`, `application/**`, `domain/**`, `infrastructure/**`처럼 기술 계층으로 다시 나누지 않는다. 다음 형태를 유지한다.

```text
com.miriyum.domain.<domain>
├── controller
├── service
├── repository
├── entity
├── dto
└── exception
```

각 하위 패키지는 실제 구성요소가 있을 때만 만든다. 빈 패키지나 미래 사용을 가정한 패키지는 만들지 않는다.

### 2.2 사용자 유형은 HTTP 경계에서만 분리한다

사용자 유형에 따라 달라지는 것은 주로 URL, 인증 주체, 요청·응답 계약이다. 따라서 필요한 도메인에서 Controller와 HTTP DTO를 다음처럼 나눈다.

```text
controller/publicapi
controller/consumer
controller/storeoperator

dto/publicapi
dto/consumer
dto/storeoperator
```

`publicapi`는 제휴사 API나 별도 외부 플랫폼 API가 아니라, 로그인하지 않은 사용자도 호출할 수 있는 공개 조회 API를 뜻한다.

Service, Repository, Entity는 사용자 유형별로 복제하지 않는다. 같은 상태 전이나 비즈니스 규칙을 `ConsumerXxxService`, `StoreOperatorXxxService`에 중복 구현하는 대신 해당 도메인의 단일 Service가 소유한다. 사용자별 Controller는 인증 주체와 HTTP 계약을 해석한 뒤 도메인 Service를 호출한다.

`consumer`, `storeoperator`처럼 도메인 이름 자체가 사용자 유형을 나타내는 계정 도메인은 `controller/consumer` 같은 중복 구조를 만들지 않는다. 이 경우 `auth`, `account`처럼 기능 목적에 따라 구분한다.

### 2.3 도메인 간 접근은 공개 Service/DTO로 제한한다

- Controller는 Repository를 직접 호출하지 않는다.
- 한 도메인은 다른 도메인의 Entity 또는 Repository를 직접 import하지 않는다.
- 도메인 간 협력은 상대 도메인이 공개한 Service와 DTO를 통해 수행한다.
- 공개 Service는 HTTP Controller 전용 계층이 아니라 도메인 간 공식 협력 경계다.
- 사용자 유형별 Controller에 상태 전이 규칙을 구현하지 않는다.

이는 ADR-001과 implementation guardrails에 정의된 3레이어 아키텍처를 그대로 적용한 것이다.

## 3. 목표 패키지 구조

### 3.1 현재 `store` 집중 영역 분리

| 현재 소유 위치 | 목표 도메인 | 책임 |
| --- | --- | --- |
| `domain.store.core` 및 매장 catalog | `domain.store` | 매장 기본 정보, 상태, 카탈로그 |
| `domain.store.schedule` | `domain.schedule` | 영업·예약 일정 버전과 게시 |
| `domain.store.closure` | `domain.schedule` 하위 closure 구조 | 정기·임시 휴무와 일정 충돌 규칙 |
| `domain.store.menu` | `domain.menu` | 메뉴 버전, 게시, 판매 상태 |
| `domain.store.search` | `domain.search` | 공개 매장 검색과 조회 projection |
| `domain.store.recommendation.geo` | `domain.search.geo` | 검색을 위한 지리 계산·조건 |

이미 독립된 `reservation`, `menuhold`, `pickup`, `consumer`, `storeoperator`, `auth`는 최상위 도메인을 유지한다.

### 3.2 사용자 유형별 Controller 경계

| 도메인 | HTTP 경계 |
| --- | --- |
| store | `publicapi`, `storeoperator` |
| menu | `publicapi`, `storeoperator` |
| search | `publicapi` |
| schedule | `storeoperator` |
| reservation | `consumer`, `storeoperator` |
| pickup | 실제 노출 계약에 맞춰 `publicapi`, `consumer`, `storeoperator` |
| consumer account | `auth`, `account` |
| storeoperator account | `auth`, `account` |

각 구분은 실제 Controller 또는 HTTP DTO가 존재할 때만 생성한다. 내부 계산 DTO나 도메인 간 협력 DTO는 사용자 유형별 HTTP DTO와 분리한다.

## 4. 두 PR로 나누는 전환 전략

### 4.1 PR1: 패키지 재구성

PR1은 Java package와 파일 위치, import, 테스트 위치만 변경한다. 다음 계약은 바꾸지 않는다.

- HTTP method와 URL
- 요청·응답 JSON 및 오류 계약
- 인증·인가와 쿠키 동작
- 비즈니스 규칙과 트랜잭션 경계
- DB schema, Flyway migration, 저장 데이터
- OpenAPI에 노출되는 경로와 schema

PR1에서 정적으로 표현 가능한 규칙은 아키텍처 테스트로 고정하고, 의미 중복 여부는 집중 테스트와 코드 리뷰 체크리스트로 확인한다.

- Controller가 Repository를 직접 호출하지 않는지 검사
- 다른 도메인의 Entity/Repository를 직접 참조하지 않는지 검사
- 도메인 간 접근이 공개 Service/DTO를 통하는지 검사
- `publicapi` Controller가 인증 principal을 필수로 요구하지 않는지 검사
- 사용자별 Controller가 해당 도메인 Service 경계를 우회하지 않는지 검사
- 사용자별 Controller에 상태 전이 규칙이 중복되지 않는지 집중 테스트와 코드 리뷰로 확인

PR1은 기존 URL과 OpenAPI 결과가 유지되는지 확인한 뒤 독립적으로 병합할 수 있어야 한다.

PR1의 패키지 승격으로 기존에는 같은 `store` 내부에 감춰져 있던 최상위 도메인 의존이 드러난다. PR1이 새 순환을 만들거나 유지해서는 안 되므로, `StoreService`가 `MenuTransactionService`를 감싸 호출하는 역방향 의존은 제거한다. 메뉴 홀드와 픽업은 Menu가 공개한 거래 검증 경계를 직접 사용하여 의존 방향을 `menu -> store`로 단방향화한다. Store 잠금 후 Menu를 잠그는 순서와 기존 트랜잭션 경계는 유지한다.

구조 테스트는 다음 회귀를 실제 타입과 전체 도메인 import 그래프를 기준으로 거부한다.

- `@Entity` 선언 타입과 Repository 선언 타입을 패키지 이름과 무관하게 식별한다.
- Controller, Store, 전체 도메인 소스 등 각 규칙의 검사 대상이 비어 있으면 실패한다.
- Service와 DTO를 포함한 전체 최상위 도메인 의존 그래프에서 새로운 순환을 거부한다.
- PR1 이전부터 존재한 `consumer <-> reservation`, `menuhold <-> reservation`만 PR1의 명시적 baseline으로 기록한다. PR2에서는 아래의 소유권 이동과 Port/Adapter 전환으로 두 baseline을 제거하고 순환 0건을 요구한다.

### 4.2 PR2: 사용자 유형별 API 경로 전환

PR2는 PR1이 `dev`에 병합된 후 최신 `dev`에서 새 브랜치를 만들어 진행한다. 다음 경로를 변경한다.

| 기존 경로 | 새 경로 |
| --- | --- |
| `/api/v1/consumer-auth/**` | `/api/v1/consumers/auth/**` |
| `/api/v1/consumer-accounts/me/**` | `/api/v1/consumers/me/**` |
| `/api/v1/store-operator-auth/**` | `/api/v1/store-operators/auth/**` |
| `/api/v1/store-operator-accounts/me/**` | `/api/v1/store-operators/me/**` |
| `/api/v1/store-operator/stores/**` | `/api/v1/store-operators/stores/**` |

다음 공개 API 경로는 이미 리소스 중심이고 호출자의 사용자 유형과 무관하므로 유지한다.

- `/api/v1/stores/**`
- `/api/v1/store-categories`
- `/api/v1/menu-categories`
- `/api/v1/store-tags`

PR2에서는 경로 문자열을 가진 모든 소비자를 하나의 변경 단위로 수정한다.

- Controller mapping
- Spring Security matcher와 JWT filter 적용 경로
- Refresh/CSRF cookie의 `Path`
- Origin/Referer 및 CSRF 검증 대상
- 인증·검색 rate limit 적용 경로
- 멱등성 요청 fingerprint에 포함되는 HTTP 경로
- Controller/OpenAPI 테스트
- 기능별 OpenAPI와 통합 OpenAPI
- 프론트엔드 생성 타입, API 호출부, 경로 상수
- 기능 spec, 공통 정책, 로컬 실행 및 배포 문서의 경로와 예제

### 4.3 PR2 공통 메뉴 거래 Facade

PR1 검수에서 메뉴 홀드와 픽업이 동일한 Store-Menu 거래 검증 흐름을 사용한다는 근거가 확인되었다. PR2에서는 `menu.service.MenuTransactionService`를 `MenuTransactionFacade`로 승격한다. 새 범용 `application` wrapper package는 만들지 않고, 기존 Facade 선례와 같이 `menu.service` 안에서 목적이 명확한 교차 도메인 조정자 하나만 둔다.

`MenuTransactionFacade`는 다음 책임만 소유한다.

1. 호출자가 시작한 거래 트랜잭션에 참여한다.
2. Store 공개 자격 계약을 먼저 호출하여 Store 잠금을 획득한다.
3. Menu를 이어서 잠그고 Store 소유 관계와 게시 버전을 검증한다.
4. 메뉴 홀드와 픽업이 공통으로 소비하는 `MenuTransactionEligibility`를 반환한다.

MenuHold와 Pickup은 Facade를 직접 사용한다. Store는 Menu 또는 Facade를 참조하지 않으며, Menu의 일반 조회·관리 Service는 기존 Store 공개 계약만 사용한다. Facade 도입은 잠금 순서, 오류 코드, DTO, HTTP 계약을 변경하지 않는다. 기존 `consumer <-> reservation`, `menuhold <-> reservation` 순환은 이 Facade의 책임이 아니며 이름만 Facade로 바꾸어 우회하지 않는다.

### 4.4 PR2 기존 도메인 순환 제거

업무 협력 자체는 유지하되 컴파일 의존을 한 방향으로 정리한다.

`consumer <-> reservation`은 예약 내역 HTTP 경계의 소유권을 바로잡아 제거한다. `ConsumerAccountController`의 예약 내역 메서드와 Reservation 요청·응답 DTO 참조를 `reservation.controller.consumer`로 이동한다. 외부 URL과 인증 principal 계약은 유지한다. Reservation은 계정 활성 상태와 예약 연락처를 확인하기 위해 Consumer 공개 Service/DTO를 계속 사용하므로 최종 의존 방향은 `reservation -> consumer`다.

`menuhold <-> reservation`은 Reservation 소유 Port와 MenuHold Adapter로 제거한다. Reservation은 MenuHold 구현 Service/DTO를 직접 import하지 않고 예약 생성·해제·이행에 필요한 `ReservationMenuHoldPort`와 전용 명령·결과 계약을 소유한다. MenuHold Adapter가 이 Port를 구현하고 기존 MenuHold Service/DTO로 변환한다. 런타임 호출은 유지되지만 소스 의존은 구현체에서 Port 방향으로 역전된다.

MenuHold의 공개 가용 수량 조회는 예약 시간 정책을 계속 사용한다. 다만 거대한 `ReservationService` 전체가 아니라 시간 해석만 공개하는 `ReservationTimeResolutionService`와 전용 계약을 사용한다. 이에 따라 MenuHold에서 Reservation으로 향하는 의존만 남고, Reservation은 MenuHold를 Port 타입으로만 인식한다. PR2 구조 테스트의 허용 순환 baseline은 빈 집합이어야 한다.

## 5. 호환성 및 배포 정책

구 경로를 위한 alias Controller, redirect, rewrite는 제공하지 않는다. 구 경로는 비즈니스 handler에 도달하거나 2xx 성공을 반환해서는 안 된다. 다만 Spring Security의 fail-closed 처리 순서 때문에 구 보호 경로의 정확한 응답이 401, 403 또는 404 중 하나가 될 수 있으므로, 호환성 테스트는 “구 경로에서 비즈니스 성공 및 상태 변경이 발생하지 않음”을 핵심 기준으로 삼는다.

경로에 묶인 Refresh/CSRF cookie는 새 `Path`로 다시 발급되어야 한다. 배포 후 기존 로그인 사용자는 재로그인이 필요할 수 있으며, 보안을 낮추기 위해 구 cookie Path를 병행 지원하지 않는다.

멱등성 fingerprint가 HTTP 경로를 포함하므로, 전환 시점에 구 경로로 시작된 미완료 요청은 새 경로와 새 멱등성 키로 다시 요청한다. 기존 키를 새 경로에 재사용하는 동작은 보장하지 않는다.

DB와 비즈니스 데이터는 변경하지 않는다. 백엔드와 프론트엔드는 같은 릴리스 계약으로 전환하고, 구 프론트엔드와 새 백엔드 또는 새 프론트엔드와 구 백엔드가 섞인 상태를 지원하지 않는다. 롤백이 필요하면 DB rollback 없이 백엔드와 프론트엔드 릴리스를 함께 이전 버전으로 되돌린다.

## 6. 오류 처리

경로 변경 전후로 도메인 오류 코드, HTTP status, 성공·실패 응답 envelope는 유지한다. 경로 재구성 자체를 이유로 새로운 인증 또는 비즈니스 정책을 추가하지 않는다.

- 새 공개 경로는 기존 공개 API와 동일하게 무인증 접근 가능해야 한다.
- 새 consumer 경로는 consumer principal만 허용한다.
- 새 store-operator 경로는 store-operator principal만 허용한다.
- 교차 namespace 토큰은 기존과 동일하게 거부한다.
- 매핑 누락이나 Security matcher 누락은 기본 fail-closed 동작을 유지한다.
- 구 경로 호출로 도메인 상태가 변경되지 않아야 한다.

## 7. 검증 전략

### 7.1 PR1 검증

1. `git diff --check`
2. Java compile과 전체 backend unit test
3. 관련 MySQL/Testcontainers integration test shard
4. 새 아키텍처 의존성 테스트
5. 패키지 이동 대상별 Controller 및 Service 집중 테스트
6. OpenAPI bundle/generation 결과에 경로·schema 의미 변경이 없는지 확인
7. frontend typecheck/test/build로 생성 계약 영향이 없는지 확인

PR1의 핵심 회귀 조건은 “동일 요청이 동일 URL에서 동일 인증·응답·상태 변경을 만든다”이다.

### 7.2 PR2 검증

1. 새 경로의 정상 요청과 권한별 성공 테스트
2. consumer/store-operator 토큰 교차 사용 거부 테스트
3. 구 경로가 비즈니스 성공이나 상태 변경을 만들지 않는 테스트
4. Refresh/CSRF cookie 이름, Secure/HttpOnly/SameSite 및 새 Path 테스트
5. Origin/Referer, CSRF, JWT filter, rate limit matcher 회귀 테스트
6. 멱등성 키의 동일 요청 재시도와 다른 경로 충돌 조건 테스트
7. 기능별 OpenAPI와 통합 OpenAPI lint/bundle 및 생성 코드 무변경 검사
8. frontend typecheck/test/build
9. backend 전체 unit/integration CI shard
10. 배포 환경에서 새 로그인, refresh, logout, 계정 조회, 매장 관리 경로 smoke test

배포 smoke test는 최소한 consumer와 store-operator namespace를 각각 확인하고, 공개 매장 검색 경로가 이전과 같이 무인증으로 동작하는지 확인한다.

## 8. 범위 제외

- Issue #69의 처리 또는 구현
- 1차 MVP에서 누락된 신규 기능 구현
- DB/Flyway schema 변경
- 인증 정책, 계정 정책, 도메인 상태 전이 규칙 변경
- 구 API 호환 계층
- 마이크로서비스 또는 Clean/Hexagonal Architecture 전환
- 사용자 유형별 Service/Repository/Entity 복제

## 9. 완료 기준

- 매장 집중 패키지가 store, schedule, menu, search 책임으로 분리된다.
- 모든 도메인이 3레이어 및 공개 Service/DTO 의존 규칙을 지킨다.
- 사용자 유형별 구분은 Controller와 HTTP DTO 경계에만 반영된다.
- PR1에서 기존 HTTP/OpenAPI/비즈니스 동작이 유지된다.
- PR2에서 새 사용자 유형별 경로가 일관되게 동작한다.
- 구 경로는 alias나 redirect 없이 비즈니스 성공을 반환하지 않는다.
- Security, JWT, cookie, CSRF, rate limit, idempotency, OpenAPI, frontend 소비 경로가 함께 전환된다.
- DB migration 없이 두 PR이 각각 전체 필수 검증을 통과한다.
- 관련 문서와 Issue #82의 완료 조건이 실제 구현과 일치한다.
