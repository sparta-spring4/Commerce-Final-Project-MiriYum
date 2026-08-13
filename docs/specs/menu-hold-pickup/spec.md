# 기능 명세: 메뉴 홀드·메뉴 수량·픽업 예약

> 문서 상태: 4단계 승인
> 적용 단계: 1차 MVP
> 도메인 소유자: 4번 팀원 — 메뉴 홀드·픽업
> 협업 검토: 2번 팀원 — 매장·메뉴 기본정보·픽업 기능 상태, 3번 팀원 — 일반 예약 조정
> 관련 정책 ID: HOLD-001~HOLD-010의 1차 범위, S-003~S-005, E-004, E-005, C-001~C-013
> OpenAPI: `docs/specs/menu-hold-pickup/openapi.yaml`
> 최종 승인일: 2026-08-05

## 범위

### 포함

- 일반 예약에서 선택할 메뉴의 시간대별 홀드 가능 수량 조회
- 예약과 함께 생성·취소·이행되는 메뉴 홀드
- 메뉴·제공 구간별 총 공급과 온라인·현장·공유 수량 풀 관리
- 시간대별 품절·판매 가능 상태
- 업종과 무관하게 픽업 기능을 활성화한 매장의 픽업 가능 조회
- 픽업 예약 생성·본인 조회·취소
- 매장 운영자의 픽업 목록·상세·취소·수령 완료

### 제외

- 메뉴 홀드만 따로 생성·확정하는 사용자 API
- 사용자에게 노출되는 10분 메뉴 임시 선점과 만료 API
- 결제·환불·픽업 결제 상태
- 품절 대체 추천과 자동 메뉴 변경
- 메뉴 대기·자동 종결
- 플랫폼 운영자 재고 보정·대사 API

## 메뉴 홀드와 일반 예약

- 일반 예약의 `menuSelections`가 비어 있으면 메뉴 홀드 행을 만들지 않는다.
- 메뉴가 있으면 `ReservationService`가 예약 소유 `ReservationMenuHoldPort`를 호출하고, `ReservationMenuHoldAdapter`가 MenuHold 생성 기능을 같은 MySQL 트랜잭션에서 연결한다.
- 수용량을 먼저 잠그고 메뉴 재고 풀을 나중에 PK 오름차순으로 잠근다.
- 모든 메뉴 수량을 확보해야 예약 `CONFIRMED`와 메뉴 홀드 `CONFIRMED`가 함께 확정된다.
- 하나라도 부족하면 가능한 메뉴만 남기거나 메뉴를 자동 제거하지 않고 예약 전체를 실패시킨다.
- 예약 취소 조정자는 멱등 기록과 Reservation 행을 잠근 뒤 수용량 버킷보다 먼저 `MenuHoldService`의 종결 선잠금 계약으로 연결 MenuHold 루트 행만 잠근다. 선잠금은 비영속 `HOLD_PRESENT` 또는 `NO_HOLD`만 반환하며 홀드 상태·항목·메뉴 수량·원장·멱등 기록을 변경하지 않는다.
- `NO_HOLD`는 잠글 MenuHold 행이 없다는 뜻이며 부재 행 잠금을 획득했다는 의미가 아니다. 동시 MenuHold 생성 배제는 선행 Reservation 행 잠금과 모든 합법적인 생성·취소 경로가 같은 Reservation 행을 먼저 잠그는 순서에 의존한다.
- 취소 조정자는 선잠금 결과와 관계없이 수용량을 처리하고, `HOLD_PRESENT`일 때만 수용량 처리 뒤 기존 공개 해제 메서드를 호출한다. `NO_HOLD`이면 메뉴 홀드 해제와 메뉴 수량 복구만 생략한다.
- 예약 취소는 `MenuHoldService`의 공개 홀드 해제 메서드로 홀드를 `RELEASED`하고 실제 사용 풀에 수량을 정확히 한 번 복구한다.
- 1차 MVP는 예약 취소·홀드 `RELEASED` 전이·복구 원장·수량 변경을 같은 MySQL 트랜잭션에서 처리한다. 복구가 실패하면 모두 롤백하고, 종결 상태를 먼저 커밋한 뒤 별도 반환 사건을 재시도하지 않는다.
- 예약 방문 완료는 홀드를 `FULFILLED`로 종결하고 수량을 복구하지 않는다.

사용자가 메뉴를 건너뛰기 위해 별도의 `/menu-holds/skip` API를 호출하게 하는 대안은 빈 홀드 상태와 프론트 순서 의존성을 만들므로 채택하지 않는다.

### 고도화 임시 MenuHold 결합

> 활성화 단계: Issue #266 — 내부 원자 결합 계약만 활성, 사용자 HTTP·자동 만료·Payment orchestration 비활성

- 기존 즉시 확정 MenuHold는 `reservationId`를 부모로 사용하고 `reservationHoldId`와 `expiresAt`이 없는 현재 계약을 유지한다. 임시 MenuHold는 `reservationHoldId`를 부모로 사용하고 연결 ReservationHold와 정확히 같은 `expiresAt`을 저장한다. 임시 그룹 확정 뒤에는 기존 최종 `reservationId`도 함께 연결한다.
- V38은 `reservation_holds(reservation_hold_id, expires_at)` 참조 unique와 `menu_holds(reservation_hold_id, expires_at)` 복합 FK를 둔다. 임시 MenuHold의 `reservationHoldId`는 unique이며 하나의 ReservationHold에 MenuHold 루트가 최대 한 건만 존재한다.
- 이 복합 FK는 공유 만료 시각을 DB에서 강제하는 대신 임시 MenuHold가 존재하는 동안 부모 `expiresAt` 변경과 부모 행 삭제를 제한한다. #266에는 선점 연장과 `reservation_holds` purge가 없으므로 이 제한을 의도적으로 수용한다. 후속 단계에서 연장 또는 purge가 필요해지면 기존 행을 검증하는 forward migration으로 FK를 제거해 앱 불변식으로 이전하거나, 부모·자식 만료를 함께 바꾸는 `ON UPDATE CASCADE` 계약으로 전환해야 한다.
- DB CHECK는 기존 행과 임시 행을 구분한다. 기존 행은 `reservationHoldId/expiresAt = NULL`, `reservationId IS NOT NULL`, 상태 `CONFIRMED|RELEASED|FULFILLED`만 허용한다. 최종 Reservation에 연결되지 않은 임시 행은 `reservationHoldId/expiresAt IS NOT NULL`, `reservationId IS NULL`, 상태 `ACTIVE|RECONCILIATION_REQUIRED|RELEASED|EXPIRED`만 허용한다. 연결된 임시 행은 세 식별·시각 필드가 모두 존재하고 상태 `CONFIRMED|RELEASED|FULFILLED`만 허용한다.
- 선택 메뉴는 Menu ID 오름차순으로 정규화하며 중복 선택 수량을 합산한다. 빈 선택은 임시 MenuHold를 만들지 않는다. replay에서 연결 행 부재는 빈 선택과만 같고, 저장된 항목의 메뉴 ID·수량은 정규 선택 목록과 정확히 같아야 한다.
- Reservation은 `ReservationTemporaryMenuHoldPort`의 scalar 명령·결과만 사용하고 `ReservationTemporaryMenuHoldAdapter`가 MenuHold 소유 공개 Service에 연결한다. 생성과 종결 Service는 `MANDATORY`로 호출자 트랜잭션에 참여하며 자체 새 트랜잭션을 시작하지 않는다.
- 생성은 수용량 버킷 뒤 메뉴 재고 버킷을 PK 오름차순으로 잠근다. 생성하는 MenuHold 루트는 새 ReservationHold에만 연결되는 hold별 배타적 행이므로 기존 MenuHold 행을 잠그지 않는다. 기존 그룹을 확인하는 replay와 종결은 ReservationHold를 먼저 잠근 뒤 연결 MenuHold 루트를 잠그는 aggregate 선행 순서를 유지한다. 확보 operation ID는 `reservation-temp-menu-acquire:{reservationHoldId}`이며 기존 case-sensitive 전역 unique와 수량 원장 replay 계약을 사용한다.
- 종결 포트는 `lockForTransition`과 `applyTransition`으로 분리한다. 전자는 ReservationHold 잠금 직후 임시 MenuHold 루트만 잠그고 상태·수량을 바꾸지 않는다. 후자는 수용량 처리 뒤 같은 잠금 행에 상태를 적용하고, `RELEASED|EXPIRED`에서만 최초 확보 원장을 기준으로 메뉴 수량을 정확히 한 번 복구한다.
- `ACTIVE`와 `RECONCILIATION_REQUIRED`는 수량을 유지한다. `CONFIRMED`는 수량을 유지하고 양의 최종 `reservationId`를 연결한다. 같은 확정 명령 replay는 같은 연결만 허용하며 다른 Reservation ID는 `COMMON_007`이다. 최종 Reservation에 연결된 임시 MenuHold는 이후 기존 예약 취소에서 `RELEASED`로 전이하며 최초 확보 원장을 기준으로 수량을 한 번만 복구하고, 방문 완료에서는 수량 복구 없이 `FULFILLED`로 전이한다. 최초 생성 명령 replay는 이 합법적인 후속 `RELEASED|FULFILLED` 상태와 최종 Reservation 연결을 최신 결과로 반환하며 추가 수량 변경을 만들지 않는다. 같은 확정 명령의 전이 replay도 최종 연결 ID가 같으면 이 후속 종결 상태를 허용하고, reconciliation replay는 이후 확정과 최종 예약 종결까지 진행된 최신 상태로 수렴한다. 최종 Reservation에 연결되지 않은 임시 상태는 이 경계에 합류하지 않는다. 기존 즉시 확정 MenuHold의 취소·이행·조회 의미와 응답은 바뀌지 않는다.
- #267의 worker·명령 시점 자동 만료·대사 orchestration과 #238의 Payment/PG 판정·최종 Reservation 생성은 이 단계에서 구현하지 않는다.
- `RECONCILIATION_REQUIRED`는 자동 만료하지 않고 수용량과 메뉴 수량을 유지한다. #266은 수렴 주체를 만들지 않으며, #267 runtime을 활성화하기 전에는 장기 체류 그룹을 탐지하는 지표·알람과 replay-safe confirm/release 수렴 절차를 함께 제공해야 한다.

## 재고 버킷과 수량 풀

재고는 메뉴 전체 전역 수량이 아니라 `menuId + serviceDate + [startTime, endTime) + policyVersion` 구간별 버킷이다.

### 예약 메뉴 가용 수량 공개 조회

`GET /api/v1/stores/{storeId}/menu-hold-availability`는 `serviceDate`, `startTime`, 선택적인
`startOffset`만 입력받는다. 종료 시각은 클라이언트가 정하지 않으며 Reservation 시간 정책이 계산한
`[startAt, serviceEndAt)` 구간을 사용한다. 응답은 계산된 offset 포함 시작·종료 시각과 IANA 시간대,
현재 Store 메뉴 후보 중 동일 구간의 온라인 재고 버킷이 존재하는 메뉴만 `menuId` 오름차순으로 반환한다.
현재 버킷이 없는 메뉴는 0으로 합성하지 않고 제외하며 `SOLD_OUT`은 수량 0을 유지한다.
조회는 잠금이나 수량 확보 없이 현재 게시 상태를 관찰하므로 이후 예약·홀드 성공을 보장하지 않는다.
Reservation이 `UNAVAILABLE`을 반환하면 소유 오류 `RESERVATION_002`를 보존하고, 도메인 간 결과의
매장·시간대·구간·정책 identity가 일치하지 않으면 `COMMON_012`로 실패 폐쇄한다.

| 필드 | 의미 |
| --- | --- |
| `totalSupply` | 해당 메뉴·구간의 확인된 전체 공급 |
| `ONLINE_HOLD` | 일반 예약 메뉴 홀드와 픽업이 우선 사용하는 온라인 수량 |
| `ONSITE` | 현장 판매 전용 수량 |
| `SHARED` | 매장 정책이 허용할 때 온라인·현장이 함께 사용할 수 있는 수량 |

- 풀 배분 합계는 `totalSupply`를 넘을 수 없다.
- 온라인 거래는 `ONSITE`를 사용하지 않고 현장 판매는 `ONLINE_HOLD`를 사용하지 않는다.
- 일반 예약 메뉴 홀드와 픽업 예약은 같은 구간의 `ONLINE_HOLD`와 허용된 `SHARED`를 공유한다.
- 실제로 사용한 풀마다 추가 전용 원장 사건을 기록하되 거래 항목을 중복 생성하지 않는다.
- 총 공급·풀 배분 변경은 이미 확정·선점된 사용량보다 낮출 수 없다.
- 메뉴 기본정보 entity에 시간대별 수량을 중복 저장하지 않는다.

## 가용성과 품절

- 공개 가용량은 온라인 거래가 사용할 수 있는 `ONLINE_HOLD` 잔여와 정책상 허용된 `SHARED` 잔여의 합이다.
- `AVAILABLE`과 `SOLD_OUT`은 메뉴 전체가 아니라 재고 버킷별 상태다.
- 매장 운영자는 재고 버킷 수정으로 `availabilityStatus`를 `SOLD_OUT`으로 바꿀 수 있다.
- `SOLD_OUT`은 신규 홀드·픽업만 막고 기존 확정 거래를 취소·변경하지 않는다.
- 수량이 복구돼도 메뉴 기본정보의 `PAUSED`, `HIDDEN` 또는 버킷의 수동 `SOLD_OUT`을 자동 해제하지 않는다.
- 다시 `AVAILABLE`로 전환할 때 메뉴 게시·공개·판매 상태와 양의 온라인 가용량을 모두 검증한다.

## 픽업 예약

### 자격과 요청

- 매장 입점 `APPROVED`, 운영 `OPEN`, `pickupEnabled=true`를 모두 만족해야 한다.
- 등록 업종·검색 카테고리·태그·요청 본문의 업종 값은 픽업 가능 판정에 사용하지 않는다.
- 일반 사용자는 `storeId`, `pickupDate`, `pickupTime`, 하나 이상의 메뉴와 수량을 제출한다.
- 같은 `menuId`가 반복되면 수량을 합산하며, 합산한 메뉴별 수량도 1 이상 100 이하여야 한다. 이를 벗어나면 `COMMON_001`로 거절한다.
- 일반 사용자 픽업 생성·상세·취소는 JWT principal만 신뢰하지 않고 공개 `ConsumerAccountService.requireActiveAccount` 계약으로 현재 활성 계정을 먼저 확인한다. 생성·취소의 멱등 replay도 이 게이트를 먼저 통과하며, 실패하면 멱등 기록·Pickup Repository·재고 서비스에 진입하지 않는다.
- 메뉴는 현재 `PUBLISHED + VISIBLE + SELLING`, 픽업 선택 가능, 제공 구간 가용 상태여야 한다.
- 픽업 제공 구간과 메뉴 재고만 사용하며 예약 수용량 인원·팀 수를 사용하지 않는다.
- 픽업 제공 시간대는 별도 슬롯 테이블을 만들지 않고 게시된 현재 메뉴 재고 버킷의 제공 구간을 재사용한다.
- 픽업 가능 조회는 Store 공개 계약에서 확인한 대상 매장의 공개·판매·픽업 선택 가능 메뉴 ID와 `pickupDate`로 현재 재고 버킷을 조회한다. MenuHold는 `storeId`나 메뉴의 매장 귀속을 직접 해석하지 않는다.
- 요청의 `pickupDate + pickupTime`은 `serviceDate == pickupDate`이고 `startTime == pickupTime`인 하나의 현재 게시 구간으로 해석하며 종료 시각은 해당 버킷에서 결정한다.
- 같은 메뉴·같은 `pickupDate`·같은 `pickupTime`에 현재 게시 구간이 둘 이상이면 종료 시각을 임의 선택하지 않는다. 해당 메뉴는 픽업 가능 조회의 그 시간대 후보에서 제외하고, 생성 요청에서는 `PICKUP_003`으로 거절한다. 같은 시작 시각이라도 메뉴별 현재 게시 구간이 각각 하나라면 정상 후보로 유지한다.
- 자격을 검증하며 잠근 동일 Store 행의 IANA 시간대가 DST 판정, `pickupAt` 계산과 거래 스냅샷의 기준이다. 재고 버킷의 시간대는 이 Store 시간대와 일치해야 한다.
- 픽업 가능 조회는 Store 시간대와 다른 버킷 구간을 후보에서 제외한다. 생성 시 일치 구간이 없거나 버킷 시간대가 Store 시간대와 다르거나 Store 시간대에서 요청 local time이 DST 누락·중복 시각이면 `PICKUP_003`으로 거절한다.
- 제공 구간 종료 local date-time은 Store 시간대에서 유일한 offset으로 해석해야 한다. DST 누락·중복 종료 시각은 사용할 수 없는 구간이며, 서버 중앙 시각이 종료 Instant와 같거나 늦으면 조회 후보에서 제외하고 생성은 `PICKUP_003`으로 거절한다.
- 픽업 생성은 자격을 검증하며 잠근 동일 Store 행의 매장명과 IANA 시간대를 거래 스냅샷으로 저장한다. 이후 Store의 매장명이나 시간대가 변경돼도 기존 픽업의 표시와 거래 시각 해석은 바뀌지 않는다.

### 생성

```text
현재 활성 일반 사용자 검증
→ 멱등 기록 선점
→ 매장 픽업 기능·제공 구간 검증
→ 재고 확보 직전 제공 구간 종료 시각 재검증
→ 메뉴 재고 풀 PK 오름차순 잠금
→ 모든 메뉴 수량 조건부 확보
→ PickupReservation CONFIRMED·항목·수량 원장 기록
→ 멱등 결과 기록
→ 하나의 MySQL 트랜잭션 커밋
```

픽업은 예약성 거래지만 일반 `Reservation`, `ReservationStatus`, `partySize=0`을 재사용하지 않는다.

- 중앙 수량 확보 성공 결과는 실제로 잠그고 조건부 차감한 현재 정책 재고 버킷 ID를 메뉴·정책 버전·수량과 함께 반환한다.
- 픽업 항목은 이 확보 결과의 재고 버킷 ID를 scalar FK로 저장한다. 가용성 조회의 과거 결과로 버킷을 추측하거나 MenuHold Repository를 직접 조회하지 않는다.
- 픽업은 논리 생성 작업마다 100자 이하의 고유 확보 `operationId`를 발급하고, 확보 성공 결과의 `operationId`를 거래의 `acquireOperationId`로 저장한다.
- 공개 확보 결과는 풀별 배분과 lock version을 노출하지 않으며, 실제 풀 복구는 최초 확보 operation의 원장을 기준으로 처리한다.

### 취소와 수령 완료

- 일반 사용자는 본인의 `CONFIRMED` 픽업을 서버 중앙 시각이 저장된 `pickupAt`보다 이른 동안만 취소할 수 있다. 정확히 같은 시각부터는 `PICKUP_006`이다.
- 소비자 취소의 기준 시각은 비트랜잭션 명령 조정 계층이 최초 시도 전에 한 번 확보하고, 같은 논리 요청의 모든 기술 재시도에서 취소 가능 판정과 `cancelledAt` 기록에 재사용한다.
- 취소는 `CANCELLED`와 실제 사용 풀의 수량 복구를 같은 트랜잭션에서 한 번만 확정한다.
- 사용자·운영자 취소는 취소 작업마다 별도 복구 `operationId`를 발급하고, 저장된 `acquireOperationId`를 `sourceAcquireOperationId`로 전달한다.
- 매장 운영자는 대상 매장의 `CONFIRMED` 픽업을 사유와 함께 `CANCELLED`로 전이하거나 `PICKED_UP`으로 전이할 수 있다.
- 소비자 선택 취소 사유와 운영자 필수 취소 사유는 값이 존재할 때 Unicode code point 기준 1자 이상 500자 이하이며 공백이 아닌 문자를 하나 이상 포함해야 한다.
- 운영자 취소도 사용자 취소와 같은 중앙 수량 복구 규칙을 사용하며 별도 재고 수정으로 대신하지 않는다.
- `PICKED_UP`은 수량을 복구하지 않는다.
- `CANCELLED`와 `PICKED_UP`은 서로 전환하거나 `CONFIRMED`로 되돌리지 않는다.
- 1차 MVP에는 `READY_FOR_PICKUP`, 결제 처리·환불·임시 선점 상태가 없다.

## 조회와 소유권

- 일반 사용자 상세는 `pickupReservationId + authenticatedConsumerAccountId`로 조회한다.
- 실제 부재와 다른 사용자 소유는 같은 `PICKUP_001` 404다.
- 매장 운영자 목록·상세는 현재 소속 검증 뒤 `storeId` 범위로 조회한다.
- 메뉴 홀드 가능 수량 조회는 공개 API지만 최종 예약·픽업 쓰기 시점에 모든 상태와 중앙 수량을 다시 검증한다.
- 검색 화면의 이전 가용량은 거래 성공 보장이 아니다.

## 운영자 재고 API

- `GET /api/v1/store-operators/stores/{storeId}/menu-inventory-buckets`로 날짜·메뉴 조건의 현재 재고 버킷을 페이지 조회한다.
- `POST /api/v1/store-operators/stores/{storeId}/menu-inventory-buckets`로 새 메뉴·제공 구간 정책 버전을 만든다.
- `PATCH /api/v1/store-operators/stores/{storeId}/menu-inventory-buckets/{inventoryBucketId}`로 총 공급·풀 배분·가용 상태의 새 버전을 게시한다.
- 요청의 menuId는 2번 매장 도메인의 현재 메뉴와 대상 매장 귀속을 검증한다.
- 수정 요청은 전체 풀 배분을 함께 제출해 부분 필드 병합으로 합계 불변식이 달라지지 않게 한다.
- 모든 운영자 명령은 대상 매장의 대표 운영자 FK 일치와 `Idempotency-Key`를 검증한다.

## 오류 코드

| 외부 코드 | HTTP | 의미 |
| --- | --- | --- |
| `MENU_HOLD_001` | 409 | 현재 메뉴 상태·자격에서 홀드할 수 없음 |
| `MENU_HOLD_002` | 409 | 요청한 구간의 온라인 메뉴 수량 부족 |
| `MENU_HOLD_003` | 404 | 재고 버킷을 찾을 수 없음 |
| `MENU_HOLD_004` | 409 | 수량 풀 배분 합계가 총 공급 수량을 초과함 |
| `MENU_HOLD_005` | 409 | 이미 사용 중인 수량보다 작게 축소하려 함 |
| `MENU_HOLD_006` | 409 | 현재 가용 상태에서 요청한 전이 불가 |
| `PICKUP_001` | 404 | 본인 또는 대상 매장 범위에서 픽업 예약을 찾을 수 없음 |
| `PICKUP_002` | 409 | 매장의 픽업 기능이 비활성화되었거나 거래 상태가 유효하지 않음 |
| `PICKUP_003` | 409 | 요청한 픽업 제공 구간을 사용할 수 없음 |
| `PICKUP_004` | 409 | 픽업 메뉴 수량 부족 |
| `PICKUP_005` | 409 | 현재 픽업 상태에서 요청한 전이 불가 |
| `PICKUP_006` | 409 | 현재 시각·정책에서 픽업 취소 불가 |

매장·메뉴 기본정보·소속 오류는 `STORE_###`를, 최종 동시성 충돌은 `COMMON_008`을 사용한다.

## 트랜잭션·동시성·재시도

- 픽업 생성·취소·수령 완료와 재고 생성·수정은 `Idempotency-Key`를 요구한다.
- 픽업 생성은 예약 수용량을 잠그지 않고 현재 활성 계정 검증 → 멱등 기록 → Store·Menu 거래 검증 → 재고 풀 확보 → 실제 확보 결과를 가진 픽업 aggregate 저장 순서로 처리한다.
- 재고 풀은 요청 순서와 관계없이 PK 오름차순으로 잠근다.
- 수량 차감·복구·풀 이동과 원장 사건을 같은 트랜잭션에서 확정한다.
- 픽업 생성·소비자 취소·운영자 취소·수령 완료는 비트랜잭션 명령 조정 계층이 트랜잭션 서비스의 각 시도를 호출한다. 각 시도는 이전 실패 트랜잭션과 분리된 새 트랜잭션이어야 한다.
- MySQL 교착 `1213`, 잠금 대기 초과 `1205`, 낙관 버전 충돌만 C-007에 따라 최대 3회 시도한다. 첫 실패 뒤 100~200ms, 두 번째 실패 뒤 300~500ms 범위의 지연을 사용하고 세 번째 실패 또는 재시도 대기 interrupt는 `COMMON_008`로 반환한다.
- 재고 부족·픽업 기능·상태 위반은 자동 재시도하지 않는다.
- Testcontainers MySQL에서 일반 예약 메뉴 홀드와 픽업이 같은 마지막 온라인 수량을 경합하는 경우를 검증한다.

## Migration·호환성 요구

- `PickupReservation`은 별도 `BIGINT` PK와 외부 문자열 ID를 사용한다.
- 픽업 항목과 메뉴 홀드 항목은 같은 재고 버킷·원장을 참조하지만 거래 테이블과 상태 enum은 분리한다.
- 기존 원장 행을 수정·삭제하지 않고 반대 방향 사건으로 복구한다.
- 메뉴 종료·매장 폐점 뒤에도 과거 항목의 메뉴명·가격·수량·제공 구간·정책 버전 스냅샷을 보존한다.
- PR #213의 `V26` migration을 먼저 병합하고 Pickup 예약 스키마는 `V27__create_pickup_reservations.sql`로 추가한다. 최신 `dev` 동기화 뒤 빈 DB clean-start와 지원 이전 스키마 upgrade가 같은 최종 상태로 수렴해야 한다.

## 인수 조건

- 메뉴를 건너뛴 일반 예약에 빈 메뉴 홀드가 없다.
- 일반 예약 홀드와 픽업의 성공 수량 합계가 같은 온라인 가용량을 초과하지 않는다.
- `ONSITE` 수량이 온라인 거래에 사용되지 않는다.
- 한 버킷의 품절이 다른 시간 구간이나 메뉴 전체 상태를 바꾸지 않는다.
- 픽업 생성이 일반 예약 행·인원·팀 수 배정을 만들지 않는다.
- 등록 업종은 픽업 가능 판정에 참여하지 않으며, 픽업 기능 상태는 일반 예약 기능에 영향을 주지 않는다.
- 정지·삭제된 일반 사용자는 멱등 replay를 포함한 픽업 생성·상세·취소에서 현재 계정 게이트로 거절되고, 실패 뒤 멱등 기록·Pickup Repository·재고 변경이 없다.
- 종료된 제공 구간은 수량이 남아 있어도 조회에 노출되지 않고 생성되지 않으며, 종료 직전만 허용하고 종료 정각부터 `PICKUP_003`이다.
- 승인된 기술 충돌은 서로 분리된 새 트랜잭션으로 최대 3회 시도하고, 업무 충돌은 재시도하지 않으며 한도 소진은 `COMMON_008`이다.
- 사용자·운영자 픽업 취소는 수량을 한 번 복구하고 수령 완료는 복구하지 않는다.
- 결제·10분 임시 선점·대체 추천·플랫폼 보정 API가 없다.

## 추천안 결정 이력

| 날짜 | 결정 | 선택 이유 |
| --- | --- | --- |
| 2026-07-28 | 메뉴 홀드는 예약 생성의 선택 항목으로만 공개 | 빈 홀드·별도 확정 API와 프론트 호출 순서 의존성 방지 |
| 2026-07-28 | 수량을 메뉴·제공 구간별 버킷으로 관리 | 전역 재고가 다른 날짜·시간의 예약을 잘못 차감하는 문제 방지 |
| 2026-07-28 | 온라인·현장·공유 풀을 명시적으로 분리 | 같은 물리 수량의 중복 판매와 채널 간 임의 차감 방지 |
| 2026-07-28 | 품절 상태를 재고 버킷 PATCH에 포함 | 동일 버킷에 별도 중복 품절 Controller를 만들지 않고 상태·수량 불변식 함께 검증 |
| 2026-07-28 | 픽업을 별도 aggregate·상태 기계로 유지 | 예약성은 보존하면서 좌석성 인원·팀 수 수용량과 분리 |
| 2026-07-28 | 픽업 endTime을 요청받지 않음 | 서버의 픽업 제공 구간과 다른 임의 시간 범위 차단 |
| 2026-07-28 | 운영자 픽업 취소를 별도 명령 리소스로 제공 | 정책상 매장 취소와 수량 복구를 범용 상태 PATCH·수동 재고 보정 없이 원자적으로 처리 |
| 2026-07-28 | 운영자 재고 버킷 조회를 쓰기 API와 함께 제공 | 관리 화면이 별도 DB 조회나 추측값으로 수정 요청을 구성하는 문제 방지 |
| 2026-08-10 | Pickup 명령에 현재 계정 게이트·종료 구간 이중 검사·외부 제한 재시도를 적용하고 migration을 V27로 배치 | 무상태 JWT의 정지 계정 접근, 종료 구간 거래, 실패 트랜잭션 재사용과 PR #213의 V26 충돌 방지 |
