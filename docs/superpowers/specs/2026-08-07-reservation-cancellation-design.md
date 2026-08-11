# 예약 취소 구현 설계 (#51)

## 문서 성격

이 문서는 GitHub Issue [#51](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/51)의 구현 설계다. 제품·정책 정본은 reservation spec, 공개 API 정본은 reservation OpenAPI이며 이 문서는 그 계약을 복제하거나 대체하지 않는다. 구현 중 충돌이 발견되면 이 문서를 근거로 정본을 바꾸지 않고 Issue와 정본 소유자에게 차이를 보고한다.

## 목표

- 일반 사용자와 매장 운영자에게 서로 분리된 예약 취소 명령 진입점을 제공한다.
- 유효한 `CONFIRMED` 예약의 상태, 수용량, 선택 메뉴 홀드와 메뉴 수량, 성공 감사, 멱등 결과를 하나의 MySQL 트랜잭션에서 정확히 한 번 확정한다.
- 같은 논리 명령의 재전송은 최초 성공 응답을 재생하고 자원이나 감사를 다시 변경하지 않는다.
- 취소 직후 응답, replay 응답, 이후 상세 조회가 같은 취소 주체와 사유를 공개한다.
- 기존 생성 시점의 정책·시간·수용량·연락처 스냅샷과 배정 이력을 보존한다.

## 비목표

- 결제, 예약금, 환불, 취소 수수료, 알림과 자동 승계를 추가하지 않는다.
- `DELETE /reservations/{id}`, 범용 상태 `PATCH`, `CANCEL_PENDING`을 만들지 않는다.
- 취소를 먼저 커밋한 뒤 비동기로 자원을 복구하지 않는다.
- 실패·거절 시도 감사나 대사 queue를 추가하지 않는다.
- 과거 `CANCELLED` 행의 actor 또는 reason을 추측해 backfill하지 않는다.
- #168의 취소 정책 selector·evaluator·snapshot 계약을 복제하거나 수정하지 않는다.
- Auth, Store, MenuHold의 Entity 또는 Repository에 직접 접근하지 않는다.

## 정본과 선행 계약

- 기능과 상태 전이: [reservation spec](../../specs/reservation/spec.md)
- HTTP 계약: [reservation OpenAPI](../../specs/reservation/openapi.yaml)
- 데이터·멱등 계약: [data and API contracts](../../07-data-and-api-contracts.md)
- 도메인 관계와 거래 스냅샷: [domain model](../../03-domain-model.md)
- 동시성·품질 gate: [quality and operations](../../09-quality-operations-and-rules.md)
- 공통 멱등·트랜잭션 계약: [MVP1 common spec](../../specs/mvp1-common/spec.md)
- 소유권과 cross-domain 경계: [MVP1 ownership](../../specs/mvp1-common/ownership.md), [integration contracts](../../../ai/integration-contracts.md)
- 실제 구현 범위와 인수 조건: GitHub Issue [#51](https://github.com/sparta-spring4/Commerce-Final-Project-MiriYum/issues/51)

#51은 #49의 예약 생성 결과와 #168의 저장 정책 버전 판정 계약을 소비한다. 취소 실행·성공 감사·응답 조립은 #51이 소유한다.

## 공개 진입점과 내부 조정

`ReservationCancellationCommandFacade`는 actor별 public entry를 명시적으로 분리한다.

- consumer entry: 인증된 consumer account ID, reservation ID, `IdempotencyKey`, `ConsumerCancellationRequest`
- store-operator entry: 인증된 operator account ID, store ID, reservation ID, `IdempotencyKey`, `StoreCancellationRequest`

두 entry는 principal namespace, actor scope, 사유 규칙과 요청 지문을 각자 구성한 뒤 하나의 private retry/orchestration 경로를 공유한다. 외부에 actor type을 임의로 받는 범용 public 메서드는 만들지 않는다. 이 구조는 consumer가 store 경로를 선택하거나 operator가 consumer 소유 조건을 우회하는 것을 타입과 호출 경계에서 막는다.

Facade는 서버 `Clock`에서 `requestedAt`을 한 번 얻는다. 제한 재시도마다 새 시각을 만들지 않고 동일한 `requestedAt`, 멱등 명령과 정규화 요청을 `ReservationService`에 전달한다. 공통 계약의 최대 3회 정책에 따라 교착, 일시 잠금 실패, 낙관 충돌만 트랜잭션 밖 지터로 다시 실행한다. 그 밖의 validation, 권한, 상태, 정책, 데이터 불변식 오류는 재시도하지 않는다.

`ReservationService`는 actor별 package/public service entry에서 read-only gate와 actor scope를 명시하고 private cancellation orchestration으로 합류한다. 공유 로직은 actor를 신뢰해 권한을 생략하지 않으며, public entry가 확정한 scope 값을 잠금 쿼리에도 포함한다.

## HTTP·인증·요청 계약

### 일반 사용자

- `POST /api/v1/reservations/{reservationId}/cancellations`
- operation ID는 `cancelReservationByConsumer`다.
- bearer 인증과 `Idempotency-Key`가 필수다.
- request body는 필수 object이고 알 수 없는 필드를 거부한다.
- `reason`은 선택 사항이고 제공하면 길이는 1~500자다. 현재 OpenAPI에 없는 trim 기반 공백-only 규칙은 추가하지 않는다.
- authenticated consumer account ID와 path `reservationId`만 소유권 selector로 사용한다. 요청에서 actor ID를 받지 않는다.

### 매장 운영자

- `POST /api/v1/store-operator/stores/{storeId}/reservations/{reservationId}/cancellations`
- operation ID는 `cancelReservationByStoreOperator`다.
- bearer 인증과 `Idempotency-Key`가 필수다.
- request body는 필수 object이고 알 수 없는 필드를 거부한다.
- `reason`은 필수이며 길이는 1~500자다. 현재 OpenAPI에 없는 trim 기반 공백-only 규칙은 추가하지 않는다.
- authenticated operator account ID와 path `storeId`, `reservationId`를 사용한다. 요청에서 actor ID나 권한을 받지 않는다.

두 경로 모두 성공 시 HTTP 200과 공통 성공 envelope의 `ReservationDetail`을 반환한다. 두 취소 endpoint의 OpenAPI 409 응답과 예시는 비-`CONFIRMED` 상태의 `RESERVATION_005`와 정책 불허·null/unknown version의 `RESERVATION_006`을 분리해 명시한다. `Idempotency-Key`는 기존 Global `IdempotencyKey.parse`만 사용하며 누락과 형식 오류의 공통 코드를 보존한다. 요청 지문에는 HTTP method, 정규화 route, 실제 path parameter와 정규화된 승인 body를 포함한다.

## 권한·존재 은닉·정책 판정

- consumer는 현재 활성 계정을 확인한 뒤 `reservationId + consumerAccountId` 조건으로 자신의 예약만 찾는다. 실제 부재와 다른 사용자 소유는 모두 `RESERVATION_001` 404다.
- operator는 현재 활성 계정과 Store의 공개 read-only 관리 권한 계약을 확인한 뒤 `reservationId + storeId` 조건으로 찾는다. 관리 권한 실패는 Store 공개 오류를 유지하고, 관리 가능한 매장 안의 실제 부재는 `RESERVATION_001`이다.
- 신규 예약 거래 자격 검사를 기존 예약 관리 권한 대신 사용하지 않는다.
- 새 명령에서 잠긴 예약이 `CONFIRMED`가 아니면 `RESERVATION_005`다.
- 잠긴 `CONFIRMED` 예약의 `cancellationPolicyVersion` 또는 legacy `startAt`이 null이면 evaluator를 호출하기 전에 `RESERVATION_006`으로 실패 폐쇄한다.
- non-null 저장 version과 평가 가능한 입력만 evaluator에 `(storedVersion, actor, status, startAt, requestedAt)` 순서로 전달한다. 알 수 없는 version에 대한 evaluator 결과도 `RESERVATION_006`이다.
- V1은 `startAt` 전·정각·후만으로 취소를 거절하지 않는다. client timestamp, client policy version, current-version 조회나 fallback을 사용하지 않는다.
- null 또는 unknown 저장 version과 null `startAt`은 현재 version이나 V1로 대체하지 않고 실패 폐쇄한다.
- 다른 도메인의 공개 오류를 Reservation 오류로 다시 매핑하지 않는다.

## 멱등 업무 키·요청 지문·correlation

Global claim/replay 업무 키와 request fingerprint, 외부 도메인 correlation의 책임을 분리한다.

```text
business key:
  (principal_namespace, principal_id, RESERVATION_CANCEL, normalizedIdempotencyKey)

consumer fingerprint:
  method + normalized route + reservationId + reason

store-operator fingerprint:
  method + normalized route + storeId + reservationId + reason

audit/MenuHold correlation:
  reservation-cancel:{consumer|store-operator}:{actorId}:{normalizedIdempotencyKey}
```

- `principal_namespace`는 `consumer | store-operator`, `principal_id`는 인증된 account ID다.
- fingerprint는 필드 순서와 null 표현을 고정한다. 같은 business key를 다른 예약·매장·사유에 재사용하면 domain effect 전에 `COMMON_007`이다.
- key는 기존 `IdempotencyKey.parse`가 검증하고 소문자 canonical form으로 정규화한 값만 사용한다.
- correlation에는 fingerprint와 audit FK가 소유하는 `reservationId`를 다시 넣지 않는다. 감사 `command_id`와 MenuHold release/inventory operation ID에 같은 값을 사용한다.
- prefix `reservation-cancel:` 19자, 최장 namespace와 구분자 `store-operator:` 15자, `Long.MAX_VALUE`와 구분자 20자, UUID 36자를 합치면 `19 + 15 + 20 + 36 = 90`자다. MenuHold와 audit의 `VARCHAR(100)` 경계 안에 들어간다.
- correlation은 global replay key나 FK가 아니다. global idempotency는 기존 4열 업무 키가, audit 중복 방지는 `reservation_id UNIQUE`가 담당한다.

## 감사 저장 설계

V26은 `reservation_cancellation_audits`를 신규 생성한다. 적용된 migration을 수정하지 않으며, 구현 시작 또는 PR 동기화 때 V26이 선점됐다면 다음 번호로 파일명을 바꾸고 Issue #51의 allowlist를 먼저 갱신한다.

### 열과 제약

| 열 | 계약 |
| --- | --- |
| `reservation_cancellation_audit_id` | `BIGINT AUTO_INCREMENT` 대리 PK |
| `reservation_id` | `BIGINT NOT NULL`, `reservations(reservation_id)`에 `ON DELETE RESTRICT` FK, `UNIQUE` |
| `actor_type` | `VARCHAR`, `CONSUMER` 또는 `STORE_OPERATOR` CHECK |
| `actor_id` | 양수 `BIGINT NOT NULL`; polymorphic FK 없음 |
| `cancellation_reason` | nullable `VARCHAR(500)`; null이 아니면 문자 길이 1~500자, operator이면 반드시 non-null CHECK; trim 기반 제약 없음 |
| `requested_at` | 최초 명령 시각 `DATETIME(6) NOT NULL` |
| `occurred_at` | 성공 확정 시각 `DATETIME(6) NOT NULL`; `occurred_at >= requested_at` CHECK |
| `before_status` | `CONFIRMED`만 허용하는 `VARCHAR` CHECK |
| `after_status` | `CANCELLED`만 허용하는 `VARCHAR` CHECK |
| `cancellation_policy_version` | 양수 `BIGINT NOT NULL` |
| `capacity_policy_version` | 양수 `BIGINT NOT NULL` |
| `command_id` | `VARCHAR(100) NOT NULL`; `reservation-cancel:{consumer|store-operator}:{actorId}:{normalizedIdempotencyKey}` |

`reservation_id UNIQUE`가 예약당 성공 취소 감사 최대 한 건을 DB에서 방어한다. `actor_id`는 actor type별로 서로 다른 계정 table을 가리키므로 단일 polymorphic FK를 두지 않는다. 활성 계정과 소유권은 멱등 선점 전 공개 서비스 gate가 검증하고 감사는 당시 type과 ID를 snapshot으로 보존한다.

`command_id`는 최대 90자의 안정적 correlation이며 `idempotency_commands.idempotency_command_id`를 복사하지 않고 FK도 두지 않는다. replay 판정은 기존 actor-scoped 멱등 복합키가 소유하며, audit의 command ID는 상관관계 추적만 담당한다. 같은 값을 MenuHold release/inventory operation ID에 재사용한다. 같은 key의 replay가 감사를 다시 쓰지 않으므로 별도 replay 의미를 audit에 추가하지 않는다.

Migration 이전 `CANCELLED` 예약은 감사가 없을 수 있다. actor와 사유를 추측하지 않고 optional audit join 결과가 없으면 `cancelledBy`와 `cancellationReason`을 null로 반환한다. V26 이후 정상 취소 경로는 성공 감사 없는 신규 `CANCELLED`를 만들지 않는다.

## 트랜잭션과 잠금 순서

모든 신규 실행은 `READ_COMMITTED`, timeout 5초의 한 transaction에서 다음 순서를 고정한다.

1. facade가 transaction 진입과 잠금 대기 전에 서버 `Clock`에서 `requestedAt`을 한 번 얻는다.
2. transaction 진입 전 또는 경합 잠금 전에 consumer active-account, 또는 operator active-account와 현재 매장 관리 권한을 공개 read-only 계약으로 확인한다.
3. actor namespace·actor ID·command type·정규화 UUID key의 idempotency row를 claim/replay한다. 이것이 첫 경합 잠금이다.
4. replay면 저장된 HTTP status·response code·resource type/id·`ReservationDetail` data 의미 payload를 즉시 반환하고 Reservation, 수용량, MenuHold, audit를 읽어 결과를 재구성하지 않는다. message나 성공 envelope 전체 원문을 저장·재생 계약에 포함하지 않는다.
5. fresh면 consumer는 `reservationId + consumerAccountId`, operator는 `reservationId + storeId` 조건을 포함한 단일 `FOR UPDATE`로 Reservation을 잠근다.
6. 잠긴 Reservation이 `CONFIRMED`인지 먼저 확인한다. `CONFIRMED`이면서 저장 `cancellationPolicyVersion` 또는 legacy `startAt`이 null이면 evaluator 호출 전에 `RESERVATION_006`으로 실패 폐쇄한다. 그 다음 non-null `(storedVersion, actor, status, startAt, requestedAt)`을 #168 evaluator의 실제 순서로 전달한다. 알 수 없는 version을 포함한 `REJECTED_BY_POLICY`는 `RESERVATION_006`이고, 그 밖의 `REJECTED_INVALID_STATE`는 `RESERVATION_005`다.
7. #167 MenuHold 공개 provider로 연결 MenuHold root를 선잠금한다. 반환 결과로 `NO_HOLD`와 `HOLD_PRESENT`를 구분한다.
8. 저장된 원본 allocation bucket ID와 현재 최신 정책에서 이 예약의 점유가 materialize된 bucket ID를 계산해 합집합하고 중복 제거한다.
9. 합집합의 전체 bucket을 단 한 번의 PK 오름차순 pessimistic locking read로 잠근다. 요청한 모든 ID가 반환됐는지 실패 폐쇄로 확인한다.
10. 각 잠긴 bucket에서 이 예약의 전체 party 인원과 팀 1건을 정확히 한 번 복구한다.
11. `HOLD_PRESENT`일 때만 #44/#167 공개 release 계약을 호출해 MenuHold를 `RELEASED`로 전이하고 메뉴 수량을 복구한다. `NO_HOLD`면 이 단계에 부작용이 없다.
12. 모든 자원 복구가 성공한 뒤 서버 `Clock`에서 `occurredAt`을 한 번 얻는다.
13. 동일한 `occurredAt`으로 Reservation을 `CANCELLED`로 전이하고 `cancelledAt`을 기록하며 성공 audit 한 건을 저장한다.
14. 취소된 Reservation, 생성 시점 menu snapshot, 성공 audit로 `ReservationDetail`을 조립하고 HTTP status·response code·resource type/id·data 의미 payload를 멱등 row에 저장한다.
15. 상태, 수용량, MenuHold·메뉴 수량, audit, 멱등 성공 결과를 함께 commit한다.

어느 단계든 실패하면 transaction 전체를 rollback한다. 결과는 Reservation `CONFIRMED`, 취소 전 수용량과 메뉴 상태, 성공 audit 없음, 성공 멱등 결과 없음이어야 한다. 제한 재시도는 같은 `requestedAt`과 멱등 명령으로 전체 transaction을 새로 실행한다.

## 수용량 재게시와 복구

원본 allocation은 생성 당시 실제로 점유한 bucket과 적용 `capacityPolicyVersion`의 불변 이력이다. 취소 후에도 allocation 행을 삭제하지 않는다.

수용량을 재게시할 때는 새 정책 version의 버킷을 만든 뒤 대상 날짜·매장의 `CONFIRMED` Reservation을 조회한다. 최신 bucket과 각 예약의 저장된 `[startAt, occupancyEndAt)`이 겹치면 해당 인원과 팀 1건을 최신 bucket의 현재 점유에 materialize한다. 기존 allocation은 유지하고 최신 version용 allocation 행은 만들지 않는다. 이는 기존 예약의 정책 version이나 원본 배정을 승계·변경하는 새 정책이 아니라 최신 설정에서 신규 가용량을 계산하기 위한 현재 점유 동기화다. 별도의 carry-over 정책이나 새 예약 정책 version으로 정의하지 않는다.

수용량 정책이 예약 이후 다시 게시될 수 있으므로 취소는 다음 두 집합을 다룬다.

- 원본 집합: `reservation_capacity_allocations`가 보존한 bucket ID 전체
- 현재 집합: 예약의 저장된 `[startAt, occupancyEndAt)`과 service date/store scope에 대해 현재 최신 정책에서 이 예약의 점유가 materialize된 bucket ID 전체

두 집합은 bucket ID로 합집합·중복 제거한다. 같은 bucket이 두 경로에서 발견돼도 인원과 팀을 두 번 감소시키지 않는다. 합집합 전체를 한 번의 `WHERE id IN (...) ORDER BY reservation_capacity_bucket_id ASC FOR UPDATE` 성격의 repository 계약으로 잠근 뒤에만 mutation한다.

각 대상 bucket은 예약 전체 party size와 팀 1개를 한 번만 감소시킨다. 원본 allocation은 각 행별로 `occupiedPeople == Reservation.party.totalCount`, `occupiedTeams == 1`, `capacityPolicyVersion == Reservation.capacityPolicyVersion`이어야 한다. 다중 버킷 예약도 각 allocation 행에 전체 party와 팀 1개가 기록되므로 allocation 행들을 합산하지 않는다. 감소 결과가 음수가 되거나 원본/현재 집합으로 요구한 bucket이 누락되면 데이터 불변식 위반으로 전체 rollback한다. 최신 정책이 원본과 같으면 dedupe 결과는 원본 bucket만 남는다. 최신 정책이 바뀌었으면 원본과 실제 점유가 존재하는 최신 bucket을 모두 복구하되, 단순히 시간 구간이 겹친다는 이유만으로 점유되지 않은 bucket을 감소시키지 않는다. 여러 번 재게시됐다면 중간 퇴역 bucket은 게시 당시 snapshot으로 유지한다.

현재 집합을 계산한 뒤 잠금 전후로 최신 policy version이 달라졌다면 stale 집합으로 복구하지 않고 전체 transaction을 rollback한다. 수용량 게시 역시 `CONFIRMED` Reservation을 bucket보다 먼저 잠가 취소와 같은 Reservation 행에서 직렬화한다.

## MenuHold 처리

Reservation을 잠근 뒤 수용량 bucket보다 먼저 MenuHold root를 공개 provider로 선잠금한다. MenuHold 내부 Entity나 Repository는 Reservation에서 참조하지 않는다.

- `NO_HOLD`: 메뉴 없는 예약이다. release와 메뉴 수량 복구를 호출하지 않는다.
- `HOLD_PRESENT`: 수용량 복구 뒤 공개 `release` 계약을 호출한다. hold 상태와 메뉴 수량은 같은 transaction에 참여한다.

MenuHold 선잠금, 수용량 bucket, 메뉴 재고 풀의 순서는 기존 종료 경로와 동일하게 유지한다. MenuHold release 실패나 메뉴 수량 복구 실패는 Reservation 상태와 수용량 복구를 포함해 모두 rollback한다.

## 응답 구성

### fresh 성공

취소된 Reservation의 기존 detail 필드, 저장된 메뉴 snapshot, 방금 저장한 cancellation audit를 결합한다. 공개 응답에는 OpenAPI의 `cancelledBy`와 `cancellationReason`만 추가하고 `cancelledAt` 필드를 새로 노출하지 않는다. 내부적으로 `Reservation.cancelledAt == audit.occurredAt`을 같은 `Instant` 불변식으로 유지한다. HTTP 200 응답의 status, response code, resource type/id와 `ReservationDetail` data 의미 payload만 멱등 결과에 저장하며 message나 성공 envelope 전체 원문은 저장하지 않는다.

### replay

최초 성공 때 멱등 row에 저장한 HTTP status, response code, resource type/id와 detail data 의미 payload를 사용해 공통 envelope를 조립한다. 현재 Reservation이나 audit를 다시 읽지 않는다. 따라서 mutable 조회 결과 변화가 최초 응답을 바꾸지 않고, 상태·수용량·메뉴·감사 mutation도 0건이다.

### 이후 상세 조회

기존 Reservation detail 조회에 cancellation audit optional join/query를 더한다. audit가 있으면 actor와 reason을 사용하고, migration 이전 `CANCELLED`처럼 audit가 없으면 두 필드를 null로 둔다. 상세 조회는 취소 결과를 추론하거나 과거 actor를 backfill하지 않는다.

## 오류와 멱등·동시성

- 미인증과 역할 오류는 공통 인증 계약을 따른다.
- consumer의 부재/타인 소유, 관리 가능한 store 안의 예약 부재는 `RESERVATION_001` 404다.
- 관리 권한 실패는 Store 공개 403/404 의미를 유지한다.
- fresh 명령이 `CANCELLED` 또는 `FULFILLED`에 도달하면 `RESERVATION_005` 409다.
- 저장 정책이 취소를 불허하면 #51이 정본의 `RESERVATION_006`으로 공개한다. V1에서는 시간 cutoff로 이 코드를 만들지 않는다.
- 같은 actor·command·key·fingerprint는 최초 200 payload를 replay한다.
- 같은 key의 다른 fingerprint는 `COMMON_007`이고 business mutation을 실행하지 않는다.
- 제한 재시도 후에도 해소되지 않은 동시 충돌은 공통 `COMMON_008`을 유지한다.
- MenuHold, Store 등 다른 도메인의 공개 오류는 변환하지 않는다.
- audit UNIQUE 충돌은 성공으로 추측하지 않는다. 기존 멱등 결과가 아닌 신규 경로라면 transaction을 실패시킨다.

동일 예약에 다른 key로 들어온 동시 취소는 Reservation `FOR UPDATE`에서 직렬화된다. 첫 transaction이 commit한 뒤 두 번째 fresh 명령은 `CANCELLED`를 보고 `RESERVATION_005`로 실패한다. 같은 key의 동시는 idempotency claim에서 직렬화되어 한 번만 business supplier에 진입한다.

## 의존성 경계

- Auth 계정 상태는 Auth의 공개 read-only 서비스만 사용한다.
- Store 관리 권한은 Store의 공개 관리 권한 계약만 사용한다.
- MenuHold 종결은 #167의 공개 prelock과 #44의 공개 release 계약만 사용한다.
- Idempotency는 Global의 공개 `IdempotencyKey`, `IdempotencyCommand`, executor 계약을 사용한다.
- 취소 정책은 #168 selector/evaluator/value type을 그대로 사용한다.
- 각 도메인의 Entity, Repository, 내부 오류를 복제하거나 직접 접근하지 않는다.

## TDD와 검증 전략

구현은 각 slice마다 가장 작은 실패 테스트를 먼저 관찰하고 최소 구현으로 통과시킨다. 문서에 적힌 명령은 실행 증거가 아니며 실제 PR Head에서 실행한 명령, 종료 코드와 결과만 완료 증거다.

### DTO·HTTP·보안

- consumer reason 생략/유효 1~500자/공백-only 별도 거절 없음/초과/unknown field
- operator reason 필수/공백-only 별도 거절 없음/유효/초과/unknown field
- 두 endpoint 409의 `RESERVATION_005`·`RESERVATION_006` 응답과 예시 정합성
- 두 POST 경로의 `Idempotency-Key` 누락·형식 오류·fingerprint path 차이
- consumer와 operator 인증 성공, 미인증, 잘못된 역할
- consumer 404 존재 은닉과 operator 관리 권한 403/404
- HTTP 200, 공통 성공 envelope와 `ReservationDetail` OpenAPI 일치
- 공개 응답에 계약되지 않은 `cancelledAt`이 추가되지 않음
- 허용한 두 POST 외 method/subpath deny 유지

### 단위·서비스

- actor별 public entry가 올바른 namespace·actor scope를 private orchestration에 전달
- `requestedAt`을 한 번 만들고 재시도 전체에서 재사용
- evaluator 입력은 실제 5개 `(storedVersion, actor, status, startAt, requestedAt)`이며 순서까지 검증
- `CONFIRMED`의 null 저장 version과 legacy null `startAt`은 evaluator 미호출·`RESERVATION_006`; unknown version은 evaluator 결과를 `RESERVATION_006`으로 유지
- V1의 startAt 전·정각·후 결과 동일
- fresh와 replay 분기, 다른 fingerprint `COMMON_007`, 다른 key 종결 상태 `RESERVATION_005`
- 원본 allocation과 현재 materialized 점유 bucket의 union/dedupe, 전체 PK ASC 단일 lock, 다중 버킷 정상 취소와 각 bucket 정확히 1회 복구
- 원본 allocation 각 행의 party·team·capacity policy version 불변식과 allocation 합산 금지
- 누락 bucket과 음수 복구 방어
- `NO_HOLD` 무부작용과 `HOLD_PRESENT` release
- audit 필드·시각·상태·정책 version·command correlation 불변식
- `Long.MAX_VALUE` actor ID와 UUID 경계의 correlation이 정확히 최대 90자이며 감사/MenuHold에 같은 값 전달
- fresh/replay/후속 detail의 actor·reason 구성

### MySQL migration·transaction·동시성

- V26 clean start와 V25→V26 upgrade
- audit FK, reservation UNIQUE, actor/reason/time/status/version CHECK와 round-trip
- migration 이전 audit 없는 `CANCELLED` detail nullable 호환
- 같은 key 동시 요청은 취소·수용량·메뉴·audit 1회
- 다른 key 동시 요청은 한 성공과 한 `RESERVATION_005`
- 정책 재게시 뒤 original+current materialized bucket의 정확한 인원·팀 복구와 다중 버킷 정상 취소 시 각 bucket 정확히 1회 복구
- `CONFIRMED` legacy null `startAt`이 `IllegalArgumentException`/500으로 새지 않고 evaluator 미호출·`RESERVATION_006`으로 종결
- 메뉴 없는 예약과 메뉴 있는 예약 각각의 종결
- 수용량, MenuHold, audit 저장, 멱등 finalize 단계별 실패 주입의 전체 rollback
- replay 시 audit 추가 0건과 자원 변화 0건

### 범위와 회귀

- Issue #51의 exact allowlist 32개와 실제 변경 경로를 대조한다. 이 문서에 목록 전체를 복제하지 않는다.
- `git diff --check`와 tracked/staged/untracked 범위를 확인한다.
- focused test를 먼저 실행하고 실제 영향이 있는 Reservation·MenuHold·Global 계약만 최소 회귀한다.
- DB 통합은 command registry에 등록된 MySQL surface를 사용한다.
- 최종 backend test/build는 현재 PR Head에서 실행하고 결과를 PR에 기록한다.

## 검토한 대안

### 범용 actor command 하나를 public API로 공개

거부한다. caller가 actor type과 scope를 조합하면 consumer/operator 권한 경계가 느슨해지고 잘못된 selector를 재사용하기 쉽다. actor별 명시 entry와 private 공유 조정이 중복을 제한하면서 권한을 선명하게 유지한다.

### 취소 전용 별도 domain service로 기존 ReservationService 밖에서 aggregate를 조정

거부한다. 기존 정본은 `ReservationService`가 Reservation transaction과 공개 도메인 조정을 소유하도록 한다. 별도 service가 같은 aggregate와 잠금 순서를 소유하면 생성·방문 완료와 transaction 규칙이 분산된다. Facade는 재시도와 requestedAt을, ReservationService는 transaction과 상태·자원 조정을 소유한다.

### Reservation 행에 actor와 reason을 직접 추가

거부한다. Reservation은 현재 상태와 terminal timestamp를 소유하고, 취소 사건의 actor·reason·정책·command 상관관계는 append-only 성공 audit가 소유한다. 감사 table은 예약당 성공 한 건을 UNIQUE로 방어하며 legacy nullable 조회도 분리한다.

### 원본 allocation만 복구하거나 현재 정책 bucket만 복구

거부한다. 원본만 복구하면 정책 재게시 뒤 승계 점유가 남을 수 있고, 현재 bucket만 복구하면 생성 당시 실제 점유가 남을 수 있다. 두 ID 집합의 합집합·중복 제거와 단일 오름차순 lock이 정확성과 잠금 순서를 함께 보장한다.

### 취소 commit 후 비동기 자원 복구

거부한다. 1·2차 MVP 정본은 상태와 수용량·메뉴 수량의 원자적 복구를 요구한다. 비동기 방식은 `CANCELLED`인데 자원이 남는 중간 상태와 별도 대사 계약을 새로 만들기 때문에 범위를 벗어난다.
