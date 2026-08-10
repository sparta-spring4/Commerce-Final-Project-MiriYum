# 예약 방문 완료 구현 설계 (#52)

## 문서 성격과 현재 단계

이 문서는 GitHub Issue [#52](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/52)의 구현 설계다. 제품·정책 정본은 Reservation 명세와 공통 정책 문서이고 공개 API 정본은 Reservation OpenAPI다. 이 문서는 정본을 복제하거나 대체하지 않으며, 구현 재게이트 뒤 확정된 허용 경로 안에서 정본을 최소 보정하기 위한 결정을 기록한다. 구현 중 정본과 충돌하면 이 문서를 근거로 정본을 임의 변경하지 않고 Issue와 문서 소유자에게 차이를 보고한다.

현재 #52는 `READY FOR DESIGN`이며 repository 쓰기는 이 문서 한 경로만 허용된다. 계획, production, test, migration, OpenAPI와 다른 정본은 아직 변경하지 않는다. 구현은 PR [#213](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/pull/213)이 `dev`에 병합되고 최신 `dev` 동기화·migration 재확인·exact implementation allowlist 확정이 끝날 때까지 `BLOCKED`다.

## 목표

- 활성 매장 운영자가 현재 대표 소유권을 가진 매장의 `CONFIRMED` 일반 예약을 `FULFILLED`로 종결한다.
- 연결된 `MenuHold`가 있으면 같은 MySQL transaction에서 수량 복구 없이 `FULFILLED`로 종결한다.
- 예약, MenuHold, 방문 완료 성공 감사와 멱등 결과를 모두 commit하거나 모두 rollback한다.
- 같은 논리 명령의 재전송은 최초 HTTP 200의 code와 data 의미 payload를 재생하고 상태나 감사를 다시 변경하지 않는다.
- 수용량, allocation, 메뉴 재고와 수량 원장을 조회·잠금·변경하지 않고 생성 당시 이력을 보존한다.
- 폐점·휴점처럼 현재 신규 거래가 불가능한 매장도 이미 확정된 예약의 방문 완료는 허용한다.

## 비목표

- QR 체크인, 회전형 QR, 노쇼 판정, 예약금 환불이나 알림 연계를 추가하지 않는다.
- 체크인·준비 완료·노쇼 같은 중간 상태를 만들지 않는다.
- 일반 사용자 방문 완료, 픽업 수령 완료 또는 범용 status `PATCH`를 만들지 않는다.
- `CANCELLED`나 `FULFILLED` 예약을 다시 활성화하거나 다른 종결 상태로 바꾸지 않는다.
- 수용량·메뉴 수량을 복구하거나 allocation·수량 원장을 삭제하지 않는다.
- 실패·거절·replay 감사를 만들지 않는다.
- 취소 facade와 감사를 범용 종결 구조로 리팩터링하지 않는다.
- Auth, Store, MenuHold의 Entity 또는 Repository에 직접 접근하지 않는다.
- 잘못된 방문 완료를 되돌리는 보정 API를 추가하지 않는다.

## 정본과 선행 계약

- 기능과 상태 전이: [Reservation 명세](../../specs/reservation/spec.md)
- HTTP 계약: [Reservation OpenAPI](../../specs/reservation/openapi.yaml)
- 공통 도메인 상태·잠금: [MVP1 domain model](../../specs/mvp1-common/domain-model.md)
- cross-domain 소유권: [MVP1 ownership](../../specs/mvp1-common/ownership.md), [integration contracts](../../../ai/integration-contracts.md)
- 제품 도메인 모델: [domain model](../../03-domain-model.md)
- 데이터·멱등 계약: [data and API contracts](../../07-data-and-api-contracts.md)
- 동시성·완료 gate: [quality and operations](../../09-quality-operations-and-rules.md)
- 방문 완료와 QR·노쇼의 단계 구분: [functional requirements](../../05-functional-requirements.md), [check-in/no-show policy](../../service-policies/09-checkin-noshow.md)
- 예약 종결 정책: [reservation policy](../../service-policies/04-reservation.md)
- 실제 범위와 인수 조건: GitHub Issue [#52](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/52)

#52는 #32의 MySQL 멱등 명령, #33의 Store 운영자 권한, #44의 MenuHold 이행 완료, #49의 예약 생성과 #51/PR #213의 Reservation 종결 구조를 소비한다. 방문 완료 실행·성공 감사·응답 조립은 Reservation이 소유한다.

## 확정 구조와 명명

취소 구조의 책임 분리를 따르되 방문 완료 전용 facade, result와 audit를 둔다. 호출 흐름은 이름만으로 책임이 드러나도록 다음으로 고정한다.

```text
StoreReservationController.fulfillReservation
→ ReservationFulfillmentCommandFacade.fulfill
→ ReservationService.fulfillStoreReservation
→ fulfillReservationWork
→ MenuHoldService.fulfill
→ ReservationFulfillmentAudit.recordSuccess
```

| 역할 | 이름 |
| --- | --- |
| 요청 DTO | `ReservationFulfillmentRequest` |
| facade | `ReservationFulfillmentCommandFacade` |
| facade public method | `fulfill(...)` |
| service public method | `ReservationService.fulfillStoreReservation(...)` |
| service private work | `fulfillReservationWork(...)` |
| controller method | `StoreReservationController.fulfillReservation(...)` |
| 결과 | `ReservationFulfillmentCommandResult` |
| 감사 entity | `ReservationFulfillmentAudit` |
| 감사 actor enum | `ReservationFulfillmentActorType` |
| 감사 factory | `recordSuccess(...)` |
| 감사 repository | `ReservationFulfillmentAuditRepository` |
| 감사 조회 | `findByReservationId(...)` |

HTTP resource 명사는 기존 계약대로 복수형 `fulfillments`, Java 도메인 동사는 `fulfill`로 통일한다. `complete`, `finishVisit`, `markFulfilled`, `transitionToFulfilled` 같은 같은 의미의 별도 동사를 만들지 않는다.

Facade의 공개 계약은 다음 형태로 고정한다.

```java
ReservationFulfillmentCommandResult fulfill(
        long operatorAccountId,
        long storeId,
        long reservationId,
        IdempotencyKey key,
        ReservationFulfillmentRequest request
)
```

Service의 공개 계약은 다음 형태로 고정한다.

```java
ReservationFulfillmentCommandResult fulfillStoreReservation(
        long operatorAccountId,
        long storeId,
        long reservationId,
        IdempotencyCommand command,
        Instant requestedAt,
        String correlationId
)
```

Facade와 Service 사이의 이름은 `correlationId`, MenuHold 공개 명령의 이름은 기존 계약대로 `operationId`, 감사 열과 field 이름은 `commandId`를 사용한다. 이름은 경계별 의미를 표현하지만 실제 값은 모두 같은 상관관계 ID다.

## 공개 진입점과 facade 책임

`ReservationFulfillmentCommandFacade.fulfill(...)`은 인증된 store-operator account ID, 실제 path의 store ID와 reservation ID, 검증·정규화된 `IdempotencyKey`, strict empty request를 받는다. 요청에서 actor ID, role, store 권한 또는 timestamp를 받지 않는다.

Facade는 다음을 소유한다.

- 서버 `Clock`에서 최초 `requestedAt`을 한 번 얻는다.
- actor namespace, actor ID, command type, normalized key와 canonical request fingerprint로 `IdempotencyCommand`를 만든다.
- 안정적 `correlationId`를 만든다.
- 교착, 일시 잠금 실패와 낙관 충돌만 공통 최대 3회 정책으로 transaction 밖에서 제한 재시도한다.
- 재시도마다 새 requested time, key, fingerprint 또는 correlation을 만들지 않는다.
- validation, 인증, 권한, 존재, 상태, MenuHold 상태와 데이터 무결성 오류는 재시도하지 않는다.
- 제한 재시도 뒤에도 해소되지 않은 기술 충돌은 `COMMON_008`로 유지한다.

`ReservationService.fulfillStoreReservation(...)`은 각 시도의 transaction과 replay 경계, store-scoped 예약 잠금, 상태 전이, MenuHold 조정, 감사와 응답 저장을 소유한다.

## 권한과 매장 운영 상태

방문 완료는 신규 거래가 아니라 이미 발생한 확정 거래의 종결이다. 따라서 현재 매장의 신규 거래 가능 상태를 요구하지 않는다.

- Auth의 현재 store-operator principal과 활성 계정 상태를 사용한다.
- Store의 공개 `requireManagementOwnership(operatorAccountId, storeId)` 계약으로 매장 존재와 현재 대표 운영자 소유권만 확인한다.
- `requireTransactionState`, 신규 거래 자격, 현재 `OPEN` 상태나 예약 모드 활성화를 검사하지 않는다.
- 매장이 `CLOSED`, `TEMPORARILY_CLOSED`, 휴무·브레이크타임 또는 임시 휴점 상태여도 기존 `CONFIRMED` 예약을 완료할 수 있다.
- 이 허용은 폐점 매장의 신규 예약을 받거나 매장을 다시 활성화한다는 뜻이 아니다.
- 현재 활성 계정과 대표 소유권은 fresh뿐 아니라 replay에서도 다시 검사한다. 권한을 잃은 actor는 과거 성공 payload를 읽을 수 없다.
- 현재 대표 운영자가 아니면 Store의 공개 `STORE_003` 의미를 유지한다.
- 폐점·휴점 자체로 `STORE_005`, `STORE_007` 또는 그 밖의 신규 거래 상태 오류를 반환하지 않는다.

Store 공개 서비스가 반환한 오류를 Reservation 오류로 재매핑하지 않는다.

## HTTP·인증·요청 계약

```http
POST /api/v1/store-operator/stores/{storeId}/reservations/{reservationId}/fulfillments
Authorization: Bearer ...
Idempotency-Key: <UUID>
Content-Type: application/json

{}
```

- OpenAPI operation ID와 controller method는 `fulfillReservation`이다.
- bearer 인증과 모든 요청의 `Idempotency-Key`가 필수다.
- header는 Spring의 기본 missing-header 오류로 보내지 않도록 `required=false`로 받은 뒤 기존 Global `IdempotencyKey.parse`에 위임한다.
- `storeId`는 양수 검증한다.
- `reservationId`는 공통 public ID 계약에 따라 `@Positive`로 선검증한다. 비양수 값은 `COMMON_001` 400이다.
- `ReservationFulfillmentRequest`는 field가 없는 strict record다.
- body object는 필수다. `{}`만 의미 있는 정상 입력이며 unknown field, body 누락과 깨진 JSON은 `COMMON_002`다.
- 성공과 replay는 모두 최초에 저장된 HTTP 200을 사용한다.
- 공통 성공 envelope의 code와 `ReservationDetailResponse` data를 반환한다.
- 공개 응답 schema를 변경하지 않고 내부 `fulfilledAt`을 새 응답 field로 노출하지 않는다.
- 구현 재게이트 뒤 OpenAPI generator를 실행한다. 생성 결과가 0 diff면 generated 경로를 허용 목록에 넣지 않고, 결정적 diff가 있으면 `frontend/src/shared/api/generated/reservation.ts`와 `frontend/src/shared/api/generated/auth-account.ts`를 exact implementation allowlist에 추가해 같은 변경으로 commit한다.

보안 matcher는 이 정확한 POST path를 store-operator 인증 경로로 허용한다. consumer token 등 잘못된 principal은 `AUTH_004`, 무인증은 `AUTH_001`이며, 기존 GET·cancellation과 알 수 없는 method/subpath의 경계를 유지한다.

## 존재 은닉과 상태 전이

권한 확인 후 단일 store-scoped pessimistic query로 `storeId + reservationId`에 일치하는 Reservation을 잠근다.

- 양수 reservation ID가 실제로 없거나 다른 매장에 속하면 `RESERVATION_001` 404다. 비양수 값은 repository 조회 전에 `COMMON_001` 400으로 거절한다.
- 잠긴 상태가 `CONFIRMED`일 때만 `FULFILLED`로 전이한다.
- `CANCELLED`, `FULFILLED`와 다른 모든 non-`CONFIRMED` 상태는 `RESERVATION_005` 409다.
- 다른 key로 이미 종결된 예약을 다시 완료하면 replay가 아니라 fresh 상태 충돌이므로 `RESERVATION_005`다.
- `CANCELLED`와 `FULFILLED`는 종결 상태이며 이후 취소, 다른 종결 상태 변경이나 재활성화를 허용하지 않는다.
- client timestamp나 현재 시각으로 방문 가능 시간, 체크인 또는 노쇼를 판정하지 않는다.

## 멱등 업무 키, 요청 지문과 correlation

Global claim/replay 업무 키, request fingerprint와 cross-domain correlation의 책임을 분리한다.

```text
business key:
  (store-operator, operatorAccountId, RESERVATION_FULFILL, normalizedIdempotencyKey)

fingerprint:
  method=POST
  route=/api/v1/store-operator/stores/{storeId}/reservations/{reservationId}/fulfillments
  storeId=<actual storeId>
  reservationId=<actual reservationId>

correlation:
  reservation-fulfill:store-operator:{operatorAccountId}:{normalizedIdempotencyKey}
```

- namespace는 `store-operator`, actor는 인증된 `operatorAccountId`, command type은 `RESERVATION_FULFILL`, key는 기존 parser가 만든 canonical UUID다.
- fingerprint의 field 순서를 고정한다. field 없는 request body에는 fingerprint field를 추가하지 않는다.
- 같은 business key를 다른 store 또는 reservation에 재사용하면 business mutation 전 `COMMON_007`이다.
- 같은 key와 같은 fingerprint는 현재 활성 계정과 대표 소유권을 다시 확인한 뒤 최초 HTTP status, response code, resource type/id와 data 의미 payload를 replay한다.
- replay는 Reservation, MenuHold와 audit를 읽어 응답을 재구성하지 않고 상태·MenuHold·감사를 재실행하지 않는다.
- 성공 message나 공통 envelope 전체 원문은 멱등 payload에 저장하지 않는다.
- correlation에는 fingerprint와 audit FK가 소유하는 reservation ID를 다시 넣지 않는다.
- 같은 correlation 값을 Service의 `correlationId`, `MenuHoldFulfillCommand.operationId`, 감사의 `commandId`로 사용한다.

길이는 prefix `reservation-fulfill:` 20자, namespace와 구분자 `store-operator:` 15자, `Long.MAX_VALUE` actor ID와 구분자 20자, UUID 36자를 합쳐 최대 91자다. 따라서 감사의 `VARCHAR(100)` 경계와 기존 MenuHold operation ID 계약 안에 들어간다. correlation은 Global idempotency FK가 아니며, replay는 Global 복합 업무 키, 감사 중복은 `reservation_id UNIQUE`가 각각 소유한다.

## 전용 성공 감사

방문 완료는 `reservation_fulfillment_audits` 전용 append-only 원장을 사용한다. 취소 감사와 합치거나 공통 transition audit로 이관하지 않는다.

| 열 | 계약 |
| --- | --- |
| `reservation_fulfillment_audit_id` | `BIGINT AUTO_INCREMENT` 대리 PK |
| `reservation_id` | `BIGINT NOT NULL`, `reservations(reservation_id)`에 `ON DELETE RESTRICT` FK, `UNIQUE` |
| `actor_type` | `VARCHAR`, `STORE_OPERATOR`만 허용하는 CHECK |
| `actor_id` | 양수 `BIGINT NOT NULL`; polymorphic FK 없음 |
| `requested_at` | 최초 명령 시각 `DATETIME(6) NOT NULL` |
| `occurred_at` | 성공 확정 시각 `DATETIME(6) NOT NULL`; `occurred_at >= requested_at` CHECK |
| `before_status` | `CONFIRMED`만 허용하는 `VARCHAR` CHECK |
| `after_status` | `FULFILLED`만 허용하는 `VARCHAR` CHECK |
| `reservation_time_policy_version` | 잠긴 Reservation snapshot에서 복사한 양수 `BIGINT NOT NULL` |
| `capacity_policy_version` | 잠긴 Reservation snapshot에서 복사한 양수 `BIGINT NOT NULL` |
| `command_id` | 최대 91자 correlation을 저장하는 `VARCHAR(100) NOT NULL`; `CHECK (CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100)` |

- `reservation_id UNIQUE`가 성공한 예약당 감사 최대 한 건을 DB에서 방어한다.
- `ReservationFulfillmentActorType`은 현재 `STORE_OPERATOR`만 정의하고 요청 값으로 actor type을 받지 않는다.
- `actor_id`는 인증·권한 확인된 operator account ID를 snapshot으로 남긴다.
- 자유 입력 `reason` field는 두지 않는다. 감사 사건과 멱등 command type의 `RESERVATION_FULFILL`이 고정된 전이 사유이며 별도 문구를 생성하지 않는다.
- `reservation_time_policy_version`과 `capacity_policy_version`은 잠긴 Reservation에서 복사만 한다. 시간·수용량 정책을 다시 판정하거나 capacity bucket을 조회하지 않고, 방문 완료에 취소 정책 version이나 evaluator를 사용하지 않는다.
- `occurredAt`을 성공 경로에서 한 번만 얻어 Reservation 내부 `fulfilledAt`과 감사에 같은 `Instant`를 기록한다.
- replay, 권한 거절, 존재·상태·MenuHold 오류와 rollback에는 감사 행을 생성하지 않는다.
- 감사 저장 실패 시 Reservation, MenuHold와 멱등 성공 결과까지 모두 rollback한다.
- audit UNIQUE 충돌을 성공이나 replay로 추측하지 않는다. Global 멱등 결과가 아닌 신규 경로라면 transaction 실패다.

## 트랜잭션과 잠금 순서

각 fresh 실행은 `READ_COMMITTED`, timeout 5초의 한 transaction에서 다음 순서를 고정한다.

1. Facade가 transaction 진입과 잠금 대기 전에 서버 `Clock`에서 `requestedAt`을 한 번 얻는다.
2. transaction 진입 전 또는 첫 경합 잠금 전에 현재 활성 store-operator 계정과 대표 운영자 소유권을 Store 공개 `requireManagementOwnership`으로 확인한다.
3. `(store-operator, operatorAccountId, RESERVATION_FULFILL, normalizedKey)` idempotency row를 claim/replay한다. 이것이 첫 경합 잠금이다.
4. replay면 저장된 HTTP 200, code, resource type/id와 data 의미 payload를 반환하고 business supplier에 진입하지 않는다.
5. fresh면 `storeId + reservationId` 조건을 포함한 단일 `FOR UPDATE`로 Reservation을 잠근다.
6. 잠긴 Reservation이 `CONFIRMED`인지 확인한다. 아니면 `RESERVATION_005`로 transaction을 종료한다.
7. `MenuHoldService.lockForTermination(reservationId)`로 연결 MenuHold root를 선잠금하고 `NO_HOLD`와 `HOLD_PRESENT`를 구분한다.
8. 서버 `Clock`에서 `occurredAt`을 한 번 얻는다.
9. 동일한 `occurredAt`으로 Reservation을 `FULFILLED`로 전이하고 내부 `fulfilledAt`을 기록한다.
10. `HOLD_PRESENT`일 때만 `MenuHoldService.fulfill(new MenuHoldFulfillCommand(reservationId, correlationId))`을 호출한다. `NO_HOLD`면 MenuHold 호출과 부작용이 없다.
11. `ReservationFulfillmentAudit.recordSuccess(...)`로 성공 감사 한 건을 저장한다.
12. 종결된 Reservation과 기존 menu snapshot으로 `ReservationDetailResponse`를 조립한다.
13. HTTP status, response code, resource type/id와 data 의미 payload를 멱등 성공 결과로 저장한다.
14. Reservation, MenuHold, 감사와 멱등 성공 결과를 함께 commit한다.

어느 단계든 실패하면 transaction 전체를 rollback한다. 결과는 Reservation `CONFIRMED`, 기존 MenuHold `CONFIRMED`, 성공 감사 없음과 성공 멱등 결과 없음이어야 한다. 제한 재시도는 같은 `requestedAt`, idempotency command와 correlation으로 전체 transaction을 새로 실행한다.

`MenuHoldService.fulfill`은 현재 공개 계약과 runtime에서 수량 원장을 변경하거나 persistence context를 clear하지 않고 잠긴 hold의 상태만 전이한다. 따라서 #51 취소가 메뉴 수량 복구 뒤 Reservation을 다시 관리 상태로 가져오는 rehydrate 패턴을 이 흐름에 복제하지 않는다. PR #213 병합 뒤 구현 재게이트에서 이 전제를 다시 확인하며, 공개 계약이 바뀌었다면 가짜 보완 대신 Issue와 설계를 재검토한다.

## MenuHold와 자원 불변식

Reservation은 MenuHold Entity·Repository에 직접 접근하지 않고 #44의 공개 계약만 사용한다.

- Reservation 잠금 뒤 `lockForTermination`으로 root 존재와 잠금 순서를 확정한다.
- `NO_HOLD`: 메뉴 선택이 없던 예약이다. 빈 hold를 만들거나 `fulfill`을 호출하지 않는다.
- `HOLD_PRESENT`: `MenuHoldFulfillCommand(reservationId, correlationId)`를 전달한다. `CONFIRMED`는 `FULFILLED`로 전이하고, 이미 `FULFILLED`면 추가 변경 없이 멱등 성공한다.
- `RELEASED`에서의 fulfill과 `NO_HOLD`·부재에 대한 직접 fulfill 호출만 공개 `MENU_HOLD_006`을 유지한다. 정상 `NO_HOLD` 분기는 fulfill을 호출하지 않는다.
- fresh 다른-key 요청의 Reservation이 이미 종결 상태면 MenuHold 처리 전에 `RESERVATION_005`로 실패하고, same-key replay는 business callback 자체를 실행하지 않는다.
- Reservation과 MenuHold의 불가능한 교차 상태를 가정해 공개 계약에 없는 별도 strict 오류를 만들지 않는다.
- fulfill은 메뉴 제공 완료만 표시하며 acquire operation, inventory bucket, 차감량과 수량 원장을 변경하지 않는다.
- capacity bucket과 allocation을 조회하거나 잠그지 않는다.
- 기존 allocation 행을 삭제하거나 점유 인원·팀 수를 감소시키지 않는다.
- Store·MenuHold 오류를 Reservation 오류로 변환하지 않는다.

방문 완료의 최소 잠금 순서는 Global idempotency row, Reservation, MenuHold root다. capacity와 inventory lock은 이 명령의 잠금 그래프에 존재하지 않는다.

## 응답 구성

### Fresh 성공

종결된 Reservation의 기존 detail field와 생성 시점 menu snapshot으로 `ReservationDetailResponse`를 만든다. 공개 schema에 `fulfilledAt`이나 감사 세부 field를 추가하지 않는다. 내부적으로 `Reservation.fulfilledAt == ReservationFulfillmentAudit.occurredAt`을 같은 `Instant` 불변식으로 유지한다. HTTP 200, 성공 response code, resource type/id와 detail data 의미 payload만 Global 멱등 결과에 저장한다.

### Replay

최초 성공 때 저장한 HTTP status, code, resource type/id와 detail data 의미 payload로 공통 envelope를 다시 조립한다. 현재 Reservation, MenuHold나 audit를 읽지 않는다. 따라서 이후 mutable 조회 변화가 최초 응답을 바꾸지 않고 mutation과 새 감사도 발생하지 않는다.

### 이후 상세 조회

공개 응답에 방문 완료 감사 field를 추가하지 않는다. 감사는 운영·무결성 추적용 append-only 기록이며 기존 상세 schema는 Reservation의 `FULFILLED` 상태를 통해 완료를 표현한다.

## 오류 계약

| 조건 | 공개 결과 |
| --- | --- |
| 무인증 | `AUTH_001` |
| consumer 등 잘못된 principal | `AUTH_004` |
| 비활성 운영자 | Auth 공개 오류 원형 유지 |
| 매장 없음 | `STORE_001` |
| 현재 대표 운영자가 아님 | `STORE_003` |
| 비양수 reservation ID | `COMMON_001` 400 |
| 양수 예약 없음·타 매장 예약 | `RESERVATION_001` 404 |
| non-`CONFIRMED` 전이 | `RESERVATION_005` 409 |
| MenuHold 상태 불일치 | `MENU_HOLD_006` |
| 같은 key의 다른 fingerprint | `COMMON_007` |
| 제한 재시도 뒤 기술 충돌 | `COMMON_008` |
| key 누락 | `COMMON_003` |
| key 형식 오류 | `COMMON_004` |
| unknown field·누락·깨진 JSON | `COMMON_002` |

폐점·휴점은 오류 조건이 아니다. 다른 도메인의 공개 `ServiceException`을 controller나 Reservation service에서 잡아 임의 재매핑하지 않는다.

## 동시성 계약

- 같은 key와 fingerprint의 병렬 요청은 Global idempotency claim에서 직렬화되어 business supplier가 한 번만 실행되고 나머지는 최초 200을 replay한다.
- 같은 예약에 다른 key로 들어온 병렬 완료는 Reservation `FOR UPDATE`에서 직렬화된다. 첫 transaction이 commit하면 두 번째 fresh 요청은 `FULFILLED`를 보고 `RESERVATION_005`다.
- 취소와 방문 완료가 같은 `CONFIRMED` 예약에서 경합하면 같은 Reservation row lock에서 직렬화되고 하나의 terminal 전이만 성공한다. loser는 commit된 terminal 상태를 보고 `RESERVATION_005`다.
- 감사의 `reservation_id UNIQUE`는 어떤 경쟁 경로에서도 성공 방문 완료 감사가 한 건을 넘지 않도록 방어한다.
- MenuHold 전이, 감사 저장 또는 멱등 finalize 실패는 모두 transaction rollback이며 부분 성공을 외부에서 관찰할 수 없다.

## 구현 재게이트 뒤 정본 보정

구현 exact allowlist에 포함된 경우에만 다음 정본을 최소 보정한다.

1. `docs/specs/reservation/spec.md`: 폐점·휴점 상태에서도 기존 확정 거래 완료 허용, 전용 성공 감사와 원자성 명시
2. `docs/specs/reservation/openapi.yaml`: fulfillment 전용 403·404·409 오류와 예시 정합성 보강
3. `docs/specs/mvp1-common/ownership.md`: 활성 계정·대표 소유권이라는 공통 관리 권한과 명령별 매장 상태 적격성 분리
4. `docs/specs/mvp1-common/domain-model.md`: idempotency → Reservation → MenuHold 잠금 순서와 수용량·재고 무복구 전이 명시
5. `docs/03-domain-model.md`: 모든 관리 요청이 현재 매장 거래 가능 상태를 요구한다는 오해를 제거하고 기존 거래 종결 예외 명시
6. `docs/specs/store-search/spec.md`: 폐점·휴점 매장의 기존 거래 종결 예외와 공개 Store 상태 의미 정렬
7. `docs/specs/mvp1-common/spec.md`: 공유 관리 권한과 명령별 거래 상태 조건의 경계 정렬
8. `docs/07-data-and-api-contracts.md`: 양수 public ID와 fulfillment 오류·멱등 계약 정렬
9. `docs/05-functional-requirements.md`: 1차 MVP의 직접 운영자 방문 완료와 QR·노쇼 고도화 분리
10. `docs/service-policies/09-checkin-noshow.md`: #52의 완료 결과를 고도화 CHECK가 보조 방문 증거로 소비하는 경계 명시
11. `docs/service-policies/04-reservation.md`: 두 정책 version snapshot과 `RESERVATION_FULFILL` 고정 전이 사유의 감사 계약 명시

보정 원칙은 “현재 대표 소유권은 공유 계약으로 항상 확인하고, 현재 매장 상태 적격성은 각 명령 계약이 결정한다”이다. 신규 거래는 기존 OPEN·운영 가능 규칙을 유지하고 예약 취소·방문 완료처럼 이미 발생한 거래의 종결만 별도 거래 가능 상태를 요구하지 않는다. #52의 운영자 직접 방문 완료가 1차 MVP의 독립 명령이며, CHECK는 이 결과를 고도화의 보조 방문 증거로 소비할 뿐 #52의 선행 조건이나 대체 실행 경로가 아니다.

## Migration 게이트

전용 `reservation_fulfillment_audits` table은 새 Flyway migration으로 추가한다. 이 설계 단계에서는 migration 번호를 고정하지 않는다. PR #213과 다른 열린 PR이 현재 최신 번호를 사용할 수 있으므로 다음 절차를 따른다.

1. PR #213의 `dev` 병합을 확인한다.
2. #52 브랜치를 최신 `dev`에 동기화한다.
3. 실제 적용된 migration과 열린 PR의 후보 번호를 확인한다.
4. 그 시점의 다음 안전한 `V<NEXT>`를 Issue exact allowlist에 기록한다.
5. 적용된 migration을 수정하지 않고 신규 파일만 추가한다.

번호 충돌은 파일명을 임의로 유지할 근거가 아니며, 구현 전에 Issue allowlist와 계획을 함께 확정한다.

## 단계 게이트와 예상 구현 범위

### 현재 설계 단계

허용 경로는 이 문서 한 개다.

```text
docs/superpowers/specs/2026-08-10-reservation-fulfillment-design.md
```

이 단계에서는 plan, production, test, migration, OpenAPI, generated code 또는 다른 정본을 변경·stage·commit하지 않는다.

### 설계 승인 이후

1. 이 설계 문서를 독립 검토하고 commit한다.
2. 사용자가 문서를 승인하면 Issue에 plan 문서 경로를 추가한다.
3. PR #213이 `dev`에 병합될 때까지 plan 작성 외 구현을 시작하지 않는다.
4. 병합 뒤 최신 `dev` 동기화, migration·열린 PR 충돌과 실제 공개 dependency를 재확인한다.
5. OpenAPI generator를 실행하고 생성 결과의 deterministic diff 여부를 확인한다.
6. production, tests, 신규 migration, 필요한 정본과 조건부 generated 타입을 포함하는 exact implementation allowlist를 Issue에 확정한다.
7. 실제 검증 명령과 인수 조건을 확정하고 `READY FOR IMPLEMENTATION`으로 재게이트한다.
8. 그 이후에만 TDD 구현을 시작한다.

경로 수와 파일 목록은 지금 고정하지 않는다. PR #213 병합, 최신 `dev` 동기화, 실제 dependency 재확인과 OpenAPI generator 실행 뒤 plan과 Issue 재게이트에서 exact allowlist를 계산한다. 후보에는 `ReservationFulfillmentActorType`, `ReservationFulfillmentRequest`와 그 DTO test, 신규 migration과 migration test가 포함된다. Reservation production이 다른 도메인 내부 타입에 직접 의존하지 않는지 고정하는 `ReservationProductionDependencyTest`도 권장 검증 후보로 평가한다. 이 후보 목록은 현재 구현 허용이나 exact path 확정이 아니다.

Store, Auth, MenuHold, capacity와 inventory 내부 파일은 현재 후보 범위에서 제외한다. 실제 의존 계약이 부족하면 타 도메인에 가짜 overload, fallback validator 또는 직접 접근을 만들지 않고 `BLOCKED`로 보고한다.

### Stack 전달 게이트

- 이 브랜치는 PR #213 HEAD 위에서 설계만 선행한 stack이다. PR #213이 `dev`에 병합되기 전에는 #52 브랜치를 push하거나 PR을 생성하지 않는다.
- 병합 뒤 최신 `dev`를 동기화하고 설계 commit을 안전하게 운반한다. 그때의 실제 graph를 확인해 merge, rebase 또는 cherry-pick 중 보존 가능한 방법을 선택하며 지금 선실행하지 않는다.
- plan이나 구현 전에 `origin/dev...HEAD`가 #52 설계·후속 허용 변경만 포함하는지 commit과 path 단위로 검증한다. #51 변경이 중복 diff로 남으면 재게이트를 완료한 것으로 보지 않는다.
- migration 번호, generator 결과와 exact allowlist를 이 정리된 branch 기준으로 다시 확정한다.

## TDD와 검증 전략

구현은 slice마다 가장 작은 실패 테스트를 먼저 관찰하고 최소 구현으로 통과시킨다. 문서에 적힌 명령은 실행 증거가 아니며 실제 PR HEAD에서 실행한 명령, 종료 코드와 결과만 완료 증거다.

### DTO·Controller·Security

- strict empty request의 `{}` 성공, unknown field·body 누락·깨진 JSON `COMMON_002`
- `Idempotency-Key` 누락·형식 오류와 같은 key/different fingerprint 충돌
- store ID와 reservation ID의 `@Positive` 검증, 비양수 ID `COMMON_001`, 양수 예약의 store-scoped `RESERVATION_001` 404
- 활성 store-operator 성공, 무인증 `AUTH_001`, 잘못된 principal `AUTH_004`
- 매장 없음 `STORE_001`, 대표 소유권 없음 `STORE_003`, closed/temporarily closed 성공
- fresh와 replay 모두 HTTP 200, 성공 envelope와 `ReservationDetailResponse`
- 공개 응답에 `fulfilledAt`과 감사 내부 field가 추가되지 않음
- 정확한 POST 외 method/subpath deny와 기존 GET·cancellation matcher 회귀 없음
- Reservation OpenAPI 403·404·409 예시와 controller 계약 정합성

### Facade

- 정확한 namespace, actor, `RESERVATION_FULFILL`, canonical key와 field 순서가 고정된 fingerprint
- fingerprint에 실제 store ID와 reservation ID가 포함되고 빈 body field는 없음
- `Long.MAX_VALUE` actor ID와 UUID에서 correlation이 정확히 최대 91자
- Service의 `correlationId`, MenuHold `operationId`와 감사 `commandId`에 같은 값 전달
- `requestedAt`을 한 번 만들고 제한 재시도 전체에서 재사용
- 교착·일시 lock·낙관 충돌만 재시도하고 validation·권한·상태·무결성 오류는 재시도하지 않음
- 재시도 소진 시 `COMMON_008`, interrupt 상태 보존

### Service·Entity·Repository

- 대표 소유권 확인이 idempotency claim 전 실행되고 replay에도 다시 적용됨
- 양수 ID의 store-scoped 단일 Reservation lock과 missing·wrong-store 404 존재 은닉
- `CONFIRMED → FULFILLED`만 성공하고 다른 상태는 `RESERVATION_005`
- `NO_HOLD` 무부작용과 `HOLD_PRESENT` 공개 fulfill 호출
- MenuHold fulfill 앞뒤 capacity, allocation, inventory와 수량 원장 접근 0건
- `occurredAt` 1회 획득과 Reservation·감사의 동일 timestamp
- 성공 감사의 actor, 요청·처리 시각, 상태, reservation time·capacity policy snapshot과 `RESERVATION_FULFILL` 고정 전이 사유·command correlation 정확성
- audit 저장 실패, MenuHold 실패와 멱등 finalize 실패의 rollback
- fresh 결과 저장과 replay의 상태·MenuHold·감사 mutation 0건
- 다른 key로 이미 종결된 예약의 `RESERVATION_005`

### Migration

- 구현 직전 확정한 `V<NEXT>` clean install
- 직전 migration에서 `V<NEXT>` upgrade
- PK, `reservation_id UNIQUE`, Reservation FK `ON DELETE RESTRICT`
- actor type, 양수 actor ID, time ordering, before/after status, 양수 reservation time·capacity policy version과 `CHAR_LENGTH(TRIM(command_id)) BETWEEN 1 AND 100` CHECK
- entity의 전체 감사 field round-trip
- 적용된 이전 migration 무변경과 번호 충돌 없음

### MySQL transaction·동시성

- HOLD_PRESENT에서 Reservation과 MenuHold가 같은 transaction으로 `FULFILLED`
- NO_HOLD 예약이 새 hold 없이 `FULFILLED`
- 수용량 bucket, allocation, 메뉴 재고와 수량 원장 before/after 불변
- 동일 key 병렬 요청의 exactly-once 상태·MenuHold·감사와 동일 replay payload
- 다른 key 병렬 완료의 성공 1건과 `RESERVATION_005` 1건
- 취소와 완료 경합의 terminal winner 1개와 loser `RESERVATION_005`
- MenuHold 완료, 감사 저장과 멱등 결과 단계별 실패 주입의 전체 rollback
- rollback 후 같은 key 재시도 가능성과 성공 감사 정확히 1개

### 범위와 회귀

- OpenAPI generator를 실행한다. 0 diff면 generated 경로를 제외하고, deterministic diff가 있으면 exact generated 두 경로를 allowlist에 추가해 함께 검증한다.
- focused DTO, facade, service, controller, migration과 MySQL integration tests를 먼저 실행한다.
- 영향 있는 Reservation, MenuHold 공개 계약과 Global idempotency test만 최소 회귀한다.
- 구현 최종 HEAD에서 전체 backend build를 실행한다.
- command registry의 `backend.wrapper.version`, `backend.test`, `backend.build`와 등록된 DB integration surface만 사용한다.
- `ReservationFulfillmentRequest` DTO test와 `ReservationProductionDependencyTest` 후보를 실제 plan·allowlist에 포함할지 최신 source에서 결정한다.
- `git diff --check`, 미완성 표식 검색과 Issue exact implementation allowlist 대조를 수행한다.
- user-owned `.idea/**`, `.superpowers/**`는 수정·stage·commit하지 않는다.

## 검토한 대안

### 매장 OPEN 상태를 방문 완료 조건으로 사용

거부한다. 현재 거래 가능 상태는 신규 예약·메뉴 주문을 받는 조건이고 방문 완료는 이미 발생한 확정 거래의 종결이다. 폐점·휴점 뒤 과거 방문을 정상 종결하지 못하게 하면 확정 거래와 감사가 영구적으로 남는다. 활성 계정과 현재 대표 소유권은 유지하되 별도 OPEN 조건을 적용하지 않는다.

### 범용 `ReservationTransitionAudit`

거부한다. 장기 확장성은 있지만 이미 구현된 취소 감사의 schema, 조회와 migration을 #52에서 재설계해야 한다. 전용 `ReservationFulfillmentAudit`가 기존 취소 구조를 흔들지 않고 성공 방문 완료의 의미와 UNIQUE 불변식을 명확히 소유한다.

### Reservation의 `fulfilledAt`과 Global 멱등 결과만 사용

거부한다. 상태와 timestamp만으로 누가 어떤 명령으로 전이했는지 독립적으로 추적할 수 없고 예약당 정확히 한 성공 사건을 DB에서 방어하기 어렵다. 전용 append-only 성공 감사가 actor, 전이, snapshot과 correlation을 보존한다.

### 취소 facade를 범용 종결 facade로 리팩터링

거부한다. 취소는 consumer/operator, 사유, 정책 판정과 자원 복구를 포함하고 방문 완료는 store-operator 전용 무복구 전이다. 공통 facade는 actor와 자원 규칙을 분기문으로 섞는다. 전용 facade와 result가 public contract를 명확하게 유지한다.

### 수용량 또는 메뉴 재고를 복구

거부한다. 방문 완료는 예약이 정상 이행됐다는 종결이며 점유와 메뉴 제공 이력을 되돌리는 취소가 아니다. Reservation과 MenuHold 상태만 `FULFILLED`로 전이하고 수용량·재고·allocation·수량 원장을 그대로 보존한다.

### MenuHold 없이 빈 hold 생성

거부한다. 기존 정본은 메뉴를 선택하지 않은 일반 예약에 MenuHold를 만들지 않는다. `NO_HOLD`는 정상 분기이며 방문 완료 때문에 새로운 aggregate나 건너뛰기 상태를 만들지 않는다.

### 방문 완료 commit 뒤 MenuHold를 별도 처리

거부한다. Reservation만 `FULFILLED`이고 MenuHold가 `CONFIRMED`인 중간 상태와 별도 대사·재시도 계약을 만든다. 공개 MenuHold 계약을 같은 transaction에 참여시켜 상태·감사·멱등 결과까지 원자적으로 확정한다.
