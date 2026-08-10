# 예약 취소 부분 수용량 복구 설계

상태: APPROVED

관련 Issue: #214
선행 구현: #51 / PR #213

## 1. 목적

수용량 정책을 확정 예약의 점유 구간과 겹치지 않거나 일부만 겹치게 재게시한 뒤에도 소비자와 매장 운영자가 해당 예약을 정상 취소할 수 있어야 한다.

취소는 다음 자원을 한 트랜잭션에서 정확히 한 번 함께 종결한다.

- 원본 `reservation_capacity_allocations`가 가리키는 버킷 점유
- 최신 수용량 정책에서 해당 예약과 실제로 겹쳐 materialize된 버킷 점유
- `HOLD_PRESENT`인 MenuHold와 연결 메뉴 수량
- Reservation의 `CONFIRMED -> CANCELLED` 전이
- 성공 감사와 멱등 성공 결과

공개 API, 오류 코드, DB schema와 멱등 replay 의미는 변경하지 않는다.

## 2. 근본 원인

수용량 재게시는 버킷 간 겹침만 금지하고 빈 구간은 허용한다. 재게시 시 확정 예약의 `[startAt, occupancyEndAt)`과 겹치는 새 버킷에만 party size와 team 1건을 materialize한다.

반면 PR #213의 취소 구현은 최신 정책 버킷이 예약 window 전체를 빈틈없이 연속 커버해야 한다고 검증한다. 따라서 정상 재게시 결과인 다음 두 경우도 `CAPACITY_POLICY_CHANGED`로 오인한다.

1. 최신 정책에 예약 window와 겹치는 버킷이 없어 current ID 집합이 비어 있는 경우
2. 최신 정책에 예약 window 일부와만 겹치는 버킷이 있어 current coverage에 빈틈이 있는 경우

이 실패는 Reservation, 수용량, MenuHold, 감사와 멱등 claim을 모두 rollback하므로 재시도만으로는 취소할 수 없다.

## 3. 확정한 의미

### 3.1 원본 집합

원본 집합은 예약 생성 당시 실제 차감 사실을 보존하는 allocation 원장이다. 기존 검증을 그대로 유지한다.

- 비어 있지 않아야 한다.
- 각 allocation의 reservation ID, party size, team 수와 policy version이 예약 snapshot과 일치해야 한다.
- 버킷 ID는 양수, 중복 없음, PK 오름차순이어야 한다.
- 잠근 원본 버킷은 store, service date와 저장 `capacityPolicyVersion`이 일치해야 한다.
- 원본 버킷은 예약 window 전체를 정확히 연속 커버해야 한다.

### 3.2 최신 current 집합

current 집합은 원장이 아니라 최신 정책 재게시 시점의 파생 점유다. `ReservationCapacityPublicationService.createBuckets()`와 동일한 반개구간 겹침 의미를 사용한다.

- 최신 version이 원본 version보다 크면 current 집합은 비어 있거나 예약 window를 일부만 커버할 수 있다.
- current에 포함된 각 버킷은 대상 store, service date와 최신 version이 일치해야 한다.
- 각 current 버킷은 `bucket.startTime < window.endTime`이고 `bucket.endTime > window.startTime`이어야 한다.
- current 전체가 window 시작부터 끝까지 이어져야 한다는 검증은 하지 않는다.
- 최신 version이 원본 version과 같으면 current ID 집합은 원본 ID 집합과 정확히 같아야 한다. 같은 version의 빈 집합·부분 집합·다른 ID는 데이터 불변식 위반이다.

`findLatestPolicyBucketIdsOverlapping()`가 반환한 각 최신 버킷은 재게시 때 동일한 겹침 조건으로 party size와 team 1건을 materialize한 대상이다. 따라서 그 집합만 복구하며, 겹치지 않은 최신 버킷은 변경하지 않는다.

## 4. 트랜잭션과 잠금

잠금 순서는 PR #213 계약을 유지한다.

```text
idempotency claim/replay
-> actor-scoped Reservation FOR UPDATE
-> stored cancellation policy evaluation
-> MenuHold root prelock
-> original allocation IDs + latest overlapping current IDs
-> dedupe 후 PK ASC 단일 bucket FOR UPDATE
-> latest policy version 재검증
-> original exact coverage / current per-bucket overlap 검증
-> 각 합집합 버킷 restore 정확히 1회
-> HOLD_PRESENT release
-> CANCELLED + audit + idempotency success
-> commit
```

예약 생성과 수용량 재게시는 Store 행 잠금으로 직렬화된다. 취소와 재게시는 Reservation 행 잠금으로 직렬화된다. current ID 관측 뒤 최신 version이 바뀌면 기존 재검증에서 전체 transaction을 rollback한다.

빈 current 집합이어도 original 집합은 반드시 존재하므로 bucket lock 요청은 비어 있지 않다.

## 5. 실패 처리

다음은 계속 `CAPACITY_POLICY_CHANGED`로 실패 폐쇄한다.

- 최신 policy version 부재, 0 이하 또는 원본 version보다 과거
- 원본 allocation 부재·중복·예약 불일치·party/team/version 불일치
- 같은 version인데 current와 original ID 집합 불일치
- 잠금 조회의 누락·추가·중복·PK 순서 불일치
- 원본 버킷의 store/date/version 또는 전체 coverage 불일치
- current 버킷의 store/date/latest version 또는 예약 window 겹침 불일치
- 잠금 뒤 latest version 변경

`restore()` underflow, MenuHold release 불일치, 감사 저장 또는 멱등 결과 저장 실패는 기존처럼 전체 transaction을 rollback한다. 부분 복구나 별도 실패 감사를 만들지 않는다.

## 6. 코드 구조

`ReservationService`에서 원본과 current 검증 책임을 분리한다.

- `validateObservedCurrentBucketIds`: null은 거절하되 빈 목록은 정규화할 수 있게 한다.
- 원본 검증: 기존 metadata 검증과 `validateCancellationCoverage`를 유지한다.
- current 검증: metadata와 각 버킷의 반개구간 겹침만 검증한다.
- 같은 version의 ID 동일성 검사는 current 빈 목록 허용보다 상위 불변식으로 유지한다.

Repository, Entity, DTO, Controller, OpenAPI와 migration은 변경하지 않는다.

## 7. 테스트 설계

### 단위 테스트

- newer version + empty current는 original만 잠그고 복구하여 취소 성공
- newer version + partial current는 original과 실제 겹치는 current만 복구하여 성공
- same version + empty/different current는 기존 오류 유지
- current wrong store/date/version/non-overlap은 오류 유지
- original coverage gap과 current/original underflow는 오류 및 rollback 유지

### MySQL 통합 테스트

- 실제 완전 비겹침 재게시 뒤 consumer 취소 성공
- 실제 완전 비겹침 재게시 뒤 store operator 취소 성공
- 실제 부분 겹침 재게시 뒤 original과 latest overlapping 버킷만 복구
- `HOLD_PRESENT`에서 MenuHold·메뉴 수량·감사·멱등 결과 동시 commit
- 실패 주입과 replay의 기존 원자성 회귀 없음
- 재게시와 취소 경합 시 latest version과 점유량 일관성 유지

## 8. 제외 범위

- 재게시 API 또는 `ReservationCapacityPolicy` validation 변경
- current version용 예약별 allocation 원장 신설
- DB migration과 기존 데이터 대사
- OpenAPI·frontend 생성물 변경
- 결제·환불·알림·자동 승계
- PR #213의 멱등·감사·공개 응답·잠금 순서 재설계
