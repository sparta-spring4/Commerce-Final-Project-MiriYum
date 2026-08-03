# 기능 명세: 일반 예약

> 문서 상태: 4단계 승인
> 적용 단계: 1차 MVP
> 도메인 소유자: 3번 팀원 — 예약
> 협업 검토: 2번 팀원 — 매장·운영시간·소속, 4번 팀원 — 선택 메뉴 홀드·수량 복구
> 관련 정책 ID: RES-001~RES-015의 1차 범위, S-001~S-003, E-003, E-005, C-001~C-013
> OpenAPI: `docs/specs/reservation/openapi.yaml`
> 최종 승인일: 2026-08-03

## 범위

### 포함

- 일반 사용자의 날짜·시간·인원 기반 즉시 확정 예약
- 선택 메뉴 홀드와 예약의 원자적 생성
- 본인 예약 상세·취소
- 매장 운영자의 매장별 예약 조회·취소·방문 완료
- 날짜·시간 구간별 예약 가능 인원·팀 수 설정
- 매장별 예약 시간 정책 버전과 실제 서비스·점유 종료 계산
- 중복 예약 방지와 동시 수용량 처리

### 제외

- 예약 변경·시간 이동·인원 변경
- 매장 승인·거절 대기
- 결제·예약금·환불·결제 상태
- 체크인·노쇼
- 대리 예약·예약 양도·단체 별도 승인
- 웨이팅 전환·자동 승계

## 공개 API 결정

| 사용자 목적 | 공개 API | 선택 이유 |
| --- | --- | --- |
| 예약 생성 | `POST /api/v1/reservations` | 메뉴 선택을 포함해 하나의 조정 유스케이스로 처리 |
| 본인 상세 | `GET /api/v1/reservations/{reservationId}` | 개인 자원 소유 조건 조회와 상세 계약 제공 |
| 본인 취소 | `POST .../{reservationId}/cancellations` | 삭제가 아니라 취소 사건·사유·자원 복구를 기록 |
| 운영자 목록·상세 | `/store-operator/stores/{storeId}/reservations` | 대상 매장 관리 권한 검증 범위를 경로에 명시 |
| 운영자 취소 | `POST .../{reservationId}/cancellations` | 사용자 취소와 경로·행위자는 분리하되 같은 예약 조정자 사용 |
| 방문 완료 | `POST .../{reservationId}/fulfillments` | 범용 status PATCH를 막고 허용 명령만 공개 |
| 수용량 게시 | `PUT .../reservation-capacities/{serviceDate}` | 날짜별 전체 버킷 설정을 새 버전으로 게시 |

`PATCH {status: ...}` 같은 범용 상태 변경 API는 허용되지 않은 전이, 결제·노쇼 상태 선도입과 담당자별 중복 구현을 유발하므로 사용하지 않는다.

## 예약 생성

### 요청

- 인증된 일반 사용자가 `storeId`, `serviceDate`, `startTime`과 성인·아동·영유아 인원수를 제출한다.
- DST 중복 현지 시각을 선택해야 할 때만 `startOffset`을 함께 제출한다. 일반 시각에 offset을 제출해도 매장 IANA 시간대와 일치해야 한다.
- `endTime`, `serviceEndAt`, `occupancyEndAt`은 요청하지 않는다. 해당 매장의 현재 Reservation 시간 정책으로 서버가 계산한다.
- 전체 동행 인원은 `adultCount + childCount + infantCount`이며 영유아도 수용량에 포함한다.
- 합계는 1명 이상이어야 하고 현재 매장 정책의 최소·최대 일행 인원 안에 있어야 한다.
- 연락처는 인증된 일반 사용자 계정의 검증된 연락처를 거래 스냅샷으로 사용한다. 요청 본문으로 다른 사람의 연락처·소유자 ID를 받지 않는다.
- `menuSelections`는 선택 사항이다. 없거나 빈 배열이면 `MenuHold`를 만들지 않는다.
- 같은 메뉴·제공 구간이 반복되면 요청 경계에서 수량을 합산하고 한 항목으로 정규화한다.

### 시간 정책과 종료 시각

- Reservation은 매장별 `slotInterval`, `serviceDuration`, `turnoverDuration`, 버전, `effectiveAt`과 상태를 소유한다.
- duration은 분 단위 정수다. `slotInterval`과 `serviceDuration`은 1~1440, `turnoverDuration`은 0~1440이며 `serviceDuration + turnoverDuration <= 1440`이다.
- 정책 상태는 Reservation 소유 타입 `DRAFT`, `SCHEDULED`, `ACTIVE`, `RETIRED`, `ACTIVATION_FAILED`를 사용한다. 현재 중앙 시각에 효력이 있는 활성 버전만 요청·확정에 사용하고 같은 매장의 활성 정책과 미래 게시 정책은 각각 하나를 넘지 않는다.
- 게시 예약 철회는 신뢰 가능한 중앙 시각이 `effectiveAt`보다 이를 때만 허용하며 효력 경계부터는 거부한다.
- 시작 시각은 자정이 아니라 Store가 반환한 현재 예약 접수 구간의 `windowStartAt`을 기준으로 `slotInterval`에 정렬한다.
- 계산·저장하는 시작 시각은 초와 나노초가 없는 분 단위여야 한다.
- `serviceEndAt = startAt + serviceDuration`, `occupancyEndAt = serviceEndAt + turnoverDuration`으로 계산한다.
- 고객 서비스 종료는 `serviceEndAt`, 수용량·중복 판정의 실제 점유 종료는 `occupancyEndAt`이다. 내부에서 모호한 `endTime`을 두 의미 중 하나로 사용하지 않는다.
- Store의 `windowEndAt`은 예약 **시작** 접수 상한이다. `serviceEndAt`이나 `occupancyEndAt`으로 사용하지 않는다.
- 입력 매장 순서·개수·중복을 보존하고 accepting 매장의 시간 정책만 한 번에 조회한다. 같은 시작 시각도 매장별 정책에 따라 서로 다른 점유 종료를 계산한다.
- 정책·시간대가 없거나 비활성이고, 슬롯이 맞지 않거나 현지 시각이 존재하지 않으며, 중복 현지 시각에 유효 offset이 없으면 해당 매장은 실패 폐쇄한다.
- 저장·비교는 `Instant`를 사용한다. `serviceDate`는 매장 현지 시작 날짜이고 실제 `startAt`, `serviceEndAt`, `occupancyEndAt`, IANA 시간대, 각 계산 offset과 duration·정책 소유 매장·버전을 거래 스냅샷으로 보존한다. 정책 소유 매장은 예약 매장과 같아야 한다.
- 고객 응답은 `serviceDate`, `timeStatus`, offset 포함 `startAt`, offset 포함 `serviceEndAt`, `timeZoneId`만 공개한다. 내부 `occupancyEndAt`은 고객 응답에 포함하지 않는다. V15 행은 `LEGACY_UNRESOLVED`와 null 시각 필드로 응답해 임의 offset 변환이나 예외 누출을 막는다.
- `[startAt, serviceEndAt)`의 영업시간·브레이크·휴무·휴점·폐점 충돌은 Store 소유의 contract-first Issue #104 / PR #106 batch 계약으로 검증한다. PR #84의 시작 접수 window만으로 이 전체 구간 검증을 완료했다고 간주하지 않는다.
- Store 검증 범위에는 `[serviceEndAt, occupancyEndAt)` turnover 구간을 포함하지 않는다. PR #106이 `dev`에 병합되기 전에는 Store 일정 판정을 Reservation에서 복제하지 않으며, 시간 정책·종료 계산 코어와 별개로 Store 검증 소비 및 최종 가용성 완료를 차단한다.

### 처리 순서

```text
멱등 기록 선점
→ 일반 사용자 계정·매장·운영·예약 시간대 검증
→ 같은 사용자·매장·겹치는 활성 예약 검증
→ 수용량 버킷 PK 오름차순 잠금
→ 선택 메뉴가 있으면 재고 풀 PK 오름차순 잠금
→ 모든 구간의 인원 수와 팀 1건 조건부 확보
→ 모든 선택 메뉴 수량 조건부 확보
→ Reservation CONFIRMED와 선택 MenuHold CONFIRMED 기록
→ 수용량 배정·수량 원장·멱등 결과 기록
→ 하나의 MySQL 트랜잭션 커밋
```

1차 MVP의 `REQUESTED`와 `CAPACITY_HELD`는 같은 생성 트랜잭션 안의 전이 단계다. 사용자에게 별도 확정 API나 10분 카운트다운을 제공하지 않으며 성공 응답은 `CONFIRMED`만 반환한다.

메뉴 수량 하나라도 부족하면 예약만 성공시키거나 가능한 메뉴만 남기지 않는다. 메뉴를 원하지 않는 사용자는 처음부터 `menuSelections`를 생략해 예약만 생성한다.

## 수용량

- 자원은 개별 테이블·좌석이 아니라 매장·업무 날짜·시간 구간별 전체 예약 가능 인원과 팀 수다.
- `[startAt, occupancyEndAt)`과 겹치는 모든 버킷에서 전체 동행 인원과 팀 1건을 함께 확보한다.
- 어느 버킷에서든 인원 또는 팀 수가 부족하면 전체 예약이 실패한다.
- 매장 운영자는 날짜별 버킷의 `maxPeople`, `maxTeams`, `minPartySize`, `maxPartySize`, `infantsAllowed`를 게시한다.
- `maxPartySize`는 `maxPeople`보다 클 수 없다.
- 새 설정은 신규 예약에 적용하며 기존 확정 예약과 배정 스냅샷을 축소·취소하지 않는다.
- 기존 점유보다 작은 한도를 게시하면 기존 예약은 유지하고 신규 가용량만 0으로 계산한다.

시간대 정의는 2번 도메인, 수용량 버킷과 판정은 3번 도메인이 소유한다. 두 담당자가 같은 설정 API를 중복 만들지 않는다.

## 중복 예약

- 같은 일반 사용자·같은 매장·겹치는 서비스 구간의 활성 예약은 하나만 허용한다.
- 동일 의도 재전송은 `Idempotency-Key`와 요청 지문이 같으면 기존 결과를 반환한다.
- 다른 멱등 키의 겹치는 예약은 자동 병합·취소하지 않고 `RESERVATION_004`로 거부한다.
- 다른 매장 또는 겹치지 않는 구간은 이 규칙만으로 차단하지 않는다.
- 동행자의 이름·전화번호·기기 지문으로 동일인을 추정하지 않는다.

## 조회와 존재 은닉

- 일반 사용자 상세는 `reservationId + authenticatedConsumerAccountId`로 한 번에 조회한다.
- 실제 부재와 다른 사용자 소유는 모두 `RESERVATION_001` 404를 반환한다.
- 다른 사용자 소유인지 알아보기 위한 두 번째 ID 단독 조회를 하지 않는다.
- 매장 운영자 조회는 먼저 계정 상태와 대상 매장의 대표 운영자 FK 일치를 검증하고, 이후 `storeId + reservationId`로 조회한다.
- 매장 운영자 목록은 날짜·상태 필터와 0 기반 페이지를 지원한다.

## 취소

- 일반 사용자는 자신의 `CONFIRMED` 예약을 취소할 수 있다.
- 매장 운영자는 유효한 소속이 있는 매장의 `CONFIRMED` 예약을 사유와 함께 취소할 수 있다.
- 취소 가능 여부는 서버 중앙 시각과 예약 당시 취소 정책 버전으로 판정한다.
- 1차 MVP에는 결제가 없으므로 환불 비율·금액·PAY 참조를 응답하지 않는다.
- 예약 상태 `CANCELLED`, 모든 수용량 배정 해제, 연결 메뉴 홀드 `RELEASED`와 메뉴 수량 복구를 한 트랜잭션에서 확정한다.
- 같은 멱등 키·지문의 재시도는 수량을 다시 복구하지 않고 기존 취소 결과를 반환한다.
- `CANCELLED`, `FULFILLED`에서 새로운 취소 명령을 실행할 수 없다.
- 취소 주체는 `CONSUMER` 또는 `STORE_OPERATOR` 사건 필드로 기록하며 상태 enum을 늘리지 않는다.

## 방문 완료

- 유효한 매장 운영자만 대상 매장의 `CONFIRMED` 예약을 `FULFILLED`로 전이할 수 있다.
- 연결된 `MenuHold`가 있으면 같은 트랜잭션에서 `FULFILLED`로 전이한다.
- 방문 완료는 메뉴 수량을 복구하지 않는다.
- 체크인 또는 노쇼를 중간 상태로 만들지 않는다.
- 이미 종결된 예약을 재활성화하거나 다른 종결 상태로 바꾸지 않는다.

## 오류 코드

| 외부 코드 | HTTP | 의미 |
| --- | --- | --- |
| `RESERVATION_001` | 404 | 본인 또는 대상 매장 범위에서 예약을 찾을 수 없음 |
| `RESERVATION_002` | 409 | 요청 시간이 영업·예약 접수 구간에 없음 |
| `RESERVATION_003` | 409 | 모든 점유 구간의 인원·팀 수를 확보할 수 없음 |
| `RESERVATION_004` | 409 | 같은 사용자·매장에 겹치는 활성 예약이 있음 |
| `RESERVATION_005` | 409 | 현재 예약 상태에서 요청한 전이 불가 |
| `RESERVATION_006` | 409 | 현재 시각·정책에서 취소 불가 |
| `RESERVATION_007` | 409 | 조회 뒤 정책·수용량 버전이 변경됨 |
| `RESERVATION_008` | 409 | 수용량 설정이 시간대·현재 점유와 충돌 |
| `RESERVATION_009` | 409 | 요청 인원이 매장 최소·최대 정책을 벗어남 |

메뉴 자격·수량 부족은 `MENU_HOLD_###`, 매장 상태·소속은 `STORE_###` 오류를 그대로 사용한다. 다른 도메인 오류를 편의상 RESERVATION 코드로 변환하지 않는다.

## 트랜잭션·동시성·재시도

- 생성·취소·방문 완료·수용량 게시는 모두 `Idempotency-Key`를 요구한다.
- 조정하는 `ReservationService`가 C-007의 5초 트랜잭션 경계와 `READ_COMMITTED`를 사용한다.
- 잠금은 E-005에 따라 멱등 기록 → 예약 aggregate → 수용량 버킷 → 메뉴 재고 풀 순서와 각 PK 오름차순을 지킨다.
- 교착·일시 잠금·낙관 버전 충돌만 최대 총 3회 새 트랜잭션으로 재시도한다.
- 수용량·중복·상태·메뉴 재고 부족은 업무 결과이므로 자동 재시도하지 않는다.
- 최종 충돌은 `COMMON_008`을 사용하고 성공이나 자원 부족을 추측하지 않는다.
- H2·mock만으로 동시성 성공을 주장하지 않고 Testcontainers MySQL에서 마지막 수용량과 메뉴 수량 경합을 검증한다.

## Migration·호환성 요구

- 예약은 `BIGINT` PK와 외부 문자열 ID를 사용한다.
- 수용량 버킷·배정 이력·정책 버전과 거래 스냅샷을 보존한다.
- 신규 예약의 `startAt`, `serviceEndAt`, `occupancyEndAt`은 실제 날짜를 포함한 `Instant`로 저장하고 계산 당시 IANA 시간대·offset·duration을 함께 보존한다.
- V15의 `serviceDate + startTime + endTime` 행은 offset을 추측해 소급 변환하지 않는다. 새 migration은 기존 값을 보존하고 Instant 스냅샷이 없는 과거 행을 신규 가용성 근거로 사용하지 않으며 고객 조회에는 `LEGACY_UNRESOLVED`를 명시한다.
- 매장 폐점·메뉴 종료·계정 정지가 과거 예약 행을 연쇄 삭제하지 않는다.
- 2차·고도화 상태를 추가할 때 기존 1차 enum 의미와 공개 코드를 재사용하지 않는다.

## 인수 조건

- 메뉴가 없는 예약은 `MenuHold` 없이 확정된다.
- 메뉴가 있는 예약은 수용량과 모든 메뉴 수량이 함께 성공하거나 전체 실패한다.
- 모든 점유 구간의 인원 수와 팀 수가 한도를 초과하지 않는다.
- 같은 멱등 키 재전송과 같은 사용자 중복 예약이 별도 예약·이중 차감을 만들지 않는다.
- 일반 사용자 개인 자원의 부재와 다른 사용자 소유가 같은 404다.
- 취소가 예약·수용량·메뉴 홀드·수량을 한 번만 종결·복구한다.
- 방문 완료가 수량을 복구하거나 체크인·노쇼 상태를 만들지 않는다.
- 범용 status PATCH, 결제·환불·NO_SHOW·CHANGE_PENDING API가 없다.
- 같은 시작 시각이라도 서로 다른 매장 시간 정책은 서로 다른 `occupancyEndAt`을 만들며 입력 순서와 중복을 보존한다.
- 고객 응답에 `timeStatus`, `serviceEndAt`, `timeZoneId`가 있고 `occupancyEndAt` 또는 모호한 `endTime`이 없다. 과거 행은 `LEGACY_UNRESOLVED`와 null 시각 필드로 안전하게 구분된다.
- 자정 넘김과 DST 누락·중복 시각이 날짜·offset 손실 없이 처리되거나 명시적으로 실패 폐쇄된다.

## 추천안 결정 이력

| 날짜 | 결정 | 선택 이유 |
| --- | --- | --- |
| 2026-08-04 | Store 전체 서비스 구간 검증은 Issue #104 / PR #106 선행 계약을 소비 | Store 일정 원본·충돌 판정을 Reservation에 복제하지 않고 `[startAt, serviceEndAt)`과 turnover 책임 경계를 유지 |
| 2026-08-03 | 서비스 종료와 실제 점유 종료를 `serviceEndAt`·`occupancyEndAt`으로 분리 | 고객 표시 의미와 수용량 점유 의미를 섞지 않고 매장별 duration 적용 |
| 2026-08-03 | 실제 시각은 Instant와 IANA 시간대·offset 스냅샷으로 보존 | 자정 넘김과 DST 중복·누락 시각을 LocalTime 비교로 손실하지 않음 |
| 2026-08-03 | Store `windowEndAt`은 시작 접수 상한으로만 소비 | PR #84의 window를 실제 서비스·점유 종료로 오해하는 결합 방지 |
| 2026-07-28 | 생성 요청에서 endTime을 받지 않음 | 서버의 현재 슬롯·서비스 소요 정책과 다른 임의 구간 요청 차단 |
| 2026-07-28 | 검증된 계정 연락처를 예약 스냅샷으로 사용 | 다른 소유자·연락처를 요청으로 주입하는 문제와 불필요한 개인정보 입력 축소 |
| 2026-07-28 | 선택 메뉴 홀드를 예약 생성에 포함 | 프론트엔드의 두 쓰기 호출로 원자성을 대신하는 구조 방지 |
| 2026-07-28 | 취소·방문 완료를 명령 리소스로 분리 | 범용 status PATCH와 허용되지 않은 상태 전이 방지 |
| 2026-07-28 | 날짜별 수용량 버킷 전체 게시 | 시간대 일부 병합 규칙 차이와 기존 예약 소급 수정 방지 |
| 2026-07-28 | 1차 중간 선점은 생성 트랜잭션 내부로 제한 | 결제 없는 단계에 사용자용 10분 선점·만료 기술을 억지로 노출하지 않음 |
