# 기능 명세: 일반 예약

> 문서 상태: 4단계 승인 (Issue #238 예약금 조정, Issue #239 예약금 취소 V2·처분 obligation, Issue #240 체크인·노쇼 활성, Issue #241 방문 결과 처분 연결)
> 적용 단계: 1차 MVP (취소 V1은 1·2차 MVP 공통), 고도화 예약금 계약·회전형 QR 체크인·운영자 노쇼
> 도메인 소유자: 3번 팀원 — 예약
> 협업 검토: 2번 팀원 — 매장·운영시간·소속, 4번 팀원 — 선택 메뉴 홀드·수량 복구
> 관련 정책 ID: RES-001~RES-015의 1차 범위, CHECK-001·CHECK-003·CHECK-005~CHECK-007·CHECK-010, HOLD-004, HOLD-007, PAY-001~PAY-010, S-001~S-003, E-003, E-005, C-001~C-013
> OpenAPI: `docs/specs/reservation/openapi.yaml`
> 최종 승인일: 2026-08-18

## 범위

### 포함

- 일반 사용자의 날짜·시간·인원 기반 즉시 확정 예약
- 선택 메뉴 홀드와 예약의 원자적 생성
- 본인 예약 상세·취소
- 매장 운영자의 매장별 예약 조회·취소·방문 완료
- 일반 사용자의 예약별 30초 회전형 opaque QR grant 발급
- 매장 운영자의 QR 스캔 방문 완료와 공통 5분 경계 뒤 필수 사유 노쇼 확정
- 날짜·시간 구간별 예약 가능 인원·팀 수 설정
- 매장별 예약 시간 정책 버전과 실제 서비스·점유 종료 계산
- 중복 예약 방지와 동시 수용량 처리
- 고도화의 예약금 선점·결제 준비·최종 확정·사용자 포기·전액 환불 보상 계약
- 고도화 신규 예약금 거래의 취소 V2, Payment 처분 obligation·worker와 최신 조회 projection

### 제외

- 예약 변경·시간 이동·인원 변경
- 매장 승인·거절 대기
- frontend 결제 SDK·화면과 노쇼 환불 정책
- 일회 확인번호·일부 인원 체크인·매장별 체크인 창/지각 설정·6시간 후보·24시간 자동 노쇼·정식 이의/정정
- 대리 예약·예약 양도·단체 별도 승인
- 웨이팅 전환·자동 승계

## 공개 API 결정

| 사용자 목적 | 공개 API | 선택 이유 |
| --- | --- | --- |
| 예약 생성 | `POST /api/v1/consumers/me/reservations` | 메뉴 선택을 포함해 하나의 조정 유스케이스로 처리 |
| 예약금 요청 최신 상태 | `GET /api/v1/consumers/me/reservation-requests/{reservationRequestId}` | 생성 replay와 최신 비동기 상태 조회를 분리하고 본인 소유 범위로 제한 |
| 예약금 요청 최종 확정 | `POST .../{reservationRequestId}/finalizations` | Payment 공개 결과만 사용한 명시적 멱등 수렴 명령 제공 |
| 예약금 요청 포기 | `POST .../{reservationRequestId}/abandonments` | 결제 전 사용자 포기와 늦은 결제 보상을 범용 상태 변경 없이 처리 |
| 본인 상세 | `GET /api/v1/consumers/me/reservations/{reservationId}` | 개인 자원 소유 조건 조회와 상세 계약 제공 |
| 본인 취소 | `POST .../{reservationId}/cancellations` | 삭제가 아니라 취소 사건·사유·자원 복구를 기록 |
| 본인 QR grant 발급 | `POST .../{reservationId}/check-in-qr-grants` | raw credential을 발급 성공 응답 한 번에만 반환하고 예약별 최신 version으로 회전 |
| 운영자 목록·상세 | `/api/v1/store-operators/stores/{storeId}/reservations` | 대상 매장 관리 권한 검증 범위를 경로에 명시 |
| 운영자 취소 | `POST .../{reservationId}/cancellations` | 사용자 취소와 경로·행위자는 분리하되 같은 예약 조정자 사용 |
| 기존 직접 방문 완료 | `POST .../{reservationId}/fulfillments` | QR·시간 판정과 독립된 보조 명령을 보존하고 범용 status PATCH를 차단 |
| 운영자 QR 체크인 | `POST /api/v1/store-operators/stores/{storeId}/reservation-check-ins` | opaque QR digest로 잠금 대상을 찾고 현재 grant·epoch·시간·상태를 잠금 뒤 재검증 |
| 운영자 노쇼 확정 | `POST .../{reservationId}/no-shows` | 정확히 `startAt + 5분`부터 필수 후보 사유로 `NO_SHOW` 종결 |
| 수용량 게시 | `PUT .../reservation-capacities/{serviceDate}` | 날짜별 전체 버킷 설정을 새 버전으로 게시 |
| 시간 정책 초안 | `PUT /api/v1/store-operators/stores/{storeId}/reservation-time-policies` | 매장별 불변 버전을 먼저 DRAFT로 저장 |
| 시간 정책 게시 | `POST .../reservation-time-policies/{version}/publications` | 즉시·예약 게시를 명시적 상태 전이로 제한 |
| 시간 정책 예약 철회 | `POST .../reservation-time-policies/{version}/publication-cancellations` | 효력 전 SCHEDULED만 DRAFT로 되돌림 |

`PATCH {status: ...}` 같은 범용 상태 변경 API는 허용되지 않은 전이와 담당자별 중복 구현을 유발하므로 사용하지 않는다. 각 취소·방문 완료·QR 체크인·노쇼 명령 리소스만 승인된 전이를 소유한다.

## 예약 생성

### 요청

- 인증된 일반 사용자가 `storeId`, `serviceDate`, `startTime`과 성인·아동·영유아 인원수를 제출한다.
- DST 중복 현지 시각을 선택해야 할 때만 `startOffset`을 함께 제출한다. 일반 시각에 offset을 제출해도 매장 IANA 시간대와 일치해야 한다.
- `endTime`, `serviceEndAt`, `occupancyEndAt`은 요청하지 않는다. 해당 매장의 현재 Reservation 시간 정책으로 서버가 계산한다.
- 전체 동행 인원은 `adultCount + childCount + infantCount`이며 영유아도 수용량에 포함한다.
- 합계는 1명 이상이어야 하고 현재 매장 정책의 최소·최대 일행 인원 안에 있어야 한다.
- 연락처는 Auth가 제공하는 일반 사용자의 `MVP 신뢰 연락처`(실제 소유 인증은 생략했지만 1차 MVP에서 사용할 수 있다고 간주한 연락처) 결과를 거래 스냅샷으로 사용한다. 요청 본문으로 다른 사람의 연락처·소유자 ID를 받지 않는다.
- Reservation은 전화번호 원문을 받거나 해석하지 않고 opaque reference(내부 구조나 실제 전화번호를 알 수 없는 무작위 참조값)와 예약 생성 당시 연락 가능 상태만 기존 스냅샷 컬럼에 저장한다.
- 1차 MVP에서 예약 생성이 성공하면 `contact_available_at_confirmation`은 항상 `true`이다. `ck_reservations_contact_available_at_confirmation` CHECK 제약이 이를 강제하며, 전화번호 또는 예약 참조가 준비되지 않으면 `false`를 저장하지 않고 `ACCOUNT_006`으로 예약 생성을 실패시킨다. 참조가 준비된 뒤 참조 저장소를 사용할 수 없는 실제 서버 장애만 `COMMON_012`로 구분한다.
- 활성 계정에 등록된 전화번호 또는 예약 참조가 없으면 Auth의 `ACCOUNT_006`을 전달하고 예약을 생성하지 않는다. 참조가 준비된 뒤 내부 참조 저장소를 사용할 수 없는 서버 상태는 `COMMON_012`로 구분한다.
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
- 고객 서비스 종료와 사용자 중복 예약 판정은 `serviceEndAt`을 사용한다. 수용량의 실제 점유 종료는 `occupancyEndAt`이며, 중복 예약은 서비스 구간 `[startAt, serviceEndAt)`, 수용량은 점유 구간 `[startAt, occupancyEndAt)`를 사용한다. 내부에서 모호한 `endTime`을 두 의미 중 하나로 사용하지 않는다.
- Store의 `windowEndAt`은 예약 **시작** 접수 상한이다. `serviceEndAt`이나 `occupancyEndAt`으로 사용하지 않는다.
- 입력 매장 순서·개수·중복을 보존하고 accepting 매장의 시간 정책만 한 번에 조회한다. 같은 시작 시각도 매장별 정책에 따라 서로 다른 점유 종료를 계산한다.
- 정책·시간대가 없거나 비활성이고, 슬롯이 맞지 않거나 현지 시각이 존재하지 않으며, 중복 현지 시각에 유효 offset이 없으면 해당 매장은 실패 폐쇄한다.
- 저장·비교는 `Instant`를 사용한다. `serviceDate`는 매장 현지 시작 날짜이고 실제 `startAt`, `serviceEndAt`, `occupancyEndAt`, IANA 시간대, 각 계산 offset과 duration·정책 소유 매장·버전을 거래 스냅샷으로 보존한다. 정책 소유 매장은 예약 매장과 같아야 한다.
- 고객 응답은 `serviceDate`, `timeStatus`, offset 포함 `startAt`, offset 포함 `serviceEndAt`, `timeZoneId`만 공개한다. 내부 `occupancyEndAt`은 고객 응답에 포함하지 않는다. V15 행은 `LEGACY_UNRESOLVED`와 null 시각 필드로 응답해 임의 offset 변환이나 예외 누출을 막는다.
- 소비자 예약 이력은 `createdAt`, `serviceDate`, `startAt`의 오름차순·내림차순 정렬을 공개하며, 각 정렬은 같은 방향의 예약 ID를 보조 키로 사용한다. 추천의 최근 방문 이력은 `FULFILLED`, `startAt,desc`, 최대 20건 계약을 사용한다.
- `[startAt, serviceEndAt)`의 영업시간·브레이크·휴무·휴점·폐점 충돌은 Store 소유의 contract-first Issue #104 / PR #106 batch 계약으로 검증한다. PR #84의 시작 접수 window만으로 이 전체 구간 검증을 완료했다고 간주하지 않는다.
- Reservation은 시간 계산에 성공한 항목만 입력 순서·개수·중복을 보존해 PR #106의 Store batch 계약에 전달한다. Store 검증 범위에는 `[serviceEndAt, occupancyEndAt)` turnover 구간을 포함하지 않는다. Store의 `NOT_ACCEPTING`은 해당 Reservation 결과의 `UNAVAILABLE`로 변환하고, batch 응답의 개수·순서·매장·구간이 요청과 일치하지 않거나 응답 상태가 없으면 입력 전체를 실패 폐쇄한다.

### 운영자 시간 정책 lifecycle

- 공개 경로는 공통 정본 C-008에 따라 모두 `/api/v1/store-operators/stores/{storeId}/reservation-time-policies` 아래에 둔다. 세 쓰기 명령은 bearer 인증과 `Idempotency-Key`를 필수로 사용하고 요청 본문에서 계정 ID·역할을 받지 않는다.
- 초안 요청은 분 단위 `slotInterval`, `serviceDuration`, `turnoverDuration`만 받는다. 게시 요청은 `publicationMode: IMMEDIATE | SCHEDULED`, 조건부 `effectiveAt`, 1~500자의 `changeReason`을 받고 철회 요청은 `changeReason`만 받는다. 알 수 없는 필드는 거부한다.
- 응답은 문자열 `storeId`, 숫자 `version`, 세 duration, 상태, nullable `effectiveAt`만 공개한다. Entity·내부 PK·감사 필드는 공개하지 않는다.
- 멱등 기록을 선점한 뒤 Store 공개 계약 `StoreService.requireSchedulePublicationAuthority()`로 Store 행을 잠그고 계정·대표 운영자 소유권·입점 승인·폐점 상태를 검증한다. Reservation은 Store Entity·Repository를 직접 참조하지 않는다.
- Store 행 잠금 아래에서 매장별 다음 version을 할당한다. 즉시 게시는 `DRAFT -> ACTIVE`와 기존 ACTIVE의 `RETIRED`를 한 트랜잭션에서 처리한다. 예약 게시는 `DRAFT -> SCHEDULED`로 전이하되 현재 ACTIVE를 유지하고, 효력 시각의 worker 전환 때만 기존 ACTIVE를 퇴역시킨다.
- 예약 게시 철회는 `now < effectiveAt`인 `SCHEDULED`만 `DRAFT`로 되돌린다. 같은 매장의 ACTIVE와 미래 SCHEDULED는 각각 최대 하나다.
- worker는 Store 공개 계약 `StoreService.inspectScheduledActivation()`을 사용한다. 일시 장애는 SCHEDULED를 유지해 다시 처리하고, 결정적인 Store 자격·상태 실패만 `ACTIVATION_FAILED`로 기록한다.
- 효력 시각이 지났지만 아직 전환되지 않은 SCHEDULED가 있으면 기존 ACTIVE를 계속 적용하지 않고 해당 매장을 실패 폐쇄한다.
- 정책 lifecycle 감사는 actor type/ID, 대상 version, 이전·새 ACTIVE version, 전이 전후 상태, 요청·효력·처리 시각, 사유, 결과, 명령 ID를 append-only로 보존한다. 기존 예약 영향 조회가 연결되기 전에는 `conflictCheckStatus=NOT_EVALUATED`, `conflictCount=null`을 기록한다.
- 공개 Service 계산 결과는 순수 scalar DTO다. JPA Entity나 `@Embeddable ReservationTimeSnapshot`을 결과 DTO 필드로 노출하지 않는다.

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

## 고도화 임시 선점 영속 계약

> 활성화 단계: Issue #264 — 계약·영속 모델만 활성, runtime과 HTTP 비활성

- 고도화의 10분 임시 선점은 기존 `Reservation`에 중간 상태를 추가하지 않고 별도 `ReservationHold` aggregate로 저장한다. 따라서 1차 MVP의 즉시 확정 `ReservationStatus`와 예약 조회·취소 응답은 바뀌지 않는다.
- 영속 상태 계약은 `ACTIVE`, `RECONCILIATION_REQUIRED`, `CONFIRMED`, `RELEASED`, `EXPIRED`다. Issue #264에는 상태 변경 service를 두지 않고 후속 #265가 승인된 명령 전이를 구현한다.
- 루트는 선점 소유 계정, 매장·시간·인원·연락 대상 스냅샷, 시간·수용량·취소 정책 버전, 생성 명령 ID, 상태 버전, 중앙 `createdAt`과 `expiresAt`을 보존한다. 클라이언트 멱등 키인 생성 명령 ID의 유일성과 replay 조회는 반드시 `(consumerAccountId, creationCommandId)` 소비자 범위로 제한한다.
- `contactAvailableAtConfirmation`은 향후 `false`를 허용하기 위한 상태가 아니라 선점 생성 시점의 연락 가능 근거 스냅샷이다. 연락할 수 없으면 hold를 만들지 않으므로 영속 행에서는 항상 `true`이고, 컬럼과 CHECK는 이 불변식의 감사 근거를 보존한다.
- `expiresAt`은 서버 중앙 `createdAt`에서 정확히 10분 뒤로만 계산하며 사용자 입력·setter·연장 필드를 제공하지 않는다.
- `reservation_hold_capacity_allocations`는 관련 서비스 구간별 버킷 ID, 점유 인원, 팀 1건과 수용량 정책 버전을 보존한다. 실제 원자 점유는 후속 #265가 담당한다.
- `reservation_hold_transition_audits`는 전이 전후 상태, 행위자, 요청·발생 시각, 시간·수용량 정책 버전과 명령 ID를 append-only로 보존하며 repository에는 삭제 API를 노출하지 않는다. 감사 명령 ID는 서버가 생성하는 전역 고유 내부 식별자이고 클라이언트 입력을 그대로 저장하지 않는다.
- `reservation_hold_warning_tasks`는 선점별 최대 한 건으로 `expiresAt - 2분` 경고 의무만 기록한다. `(reservationHoldId, createdAt)` 복합 FK로 실제 선점의 10분 시각창에 결합하며, Notification 계약이 준비되기 전에는 채널·본문·provider·발송 재시도 상태를 소유하지 않는다.
- V31은 네 영속 테이블의 FK, 허용 상태, 정책·수량 양수, 명령 멱등성, 정확한 10분/8분 시각식을 MySQL 제약으로 검증한다.
- MenuHold와 같은 만료 시각으로 묶는 원자 선점은 #266, 중복 worker·명령 시점 만료·대사 runtime은 #267, Payment 준비와 최종 예약 확정은 #238에서 순서대로 활성화한다.

### 수용량 선점·종결 명령 계약

> 활성화 단계: Issue #265 — 수용량 선점·명시적 종결만 활성, HTTP·MenuHold·명령 시점 자동 만료 비활성

- 명령 진입점은 기술적 MySQL deadlock·lock timeout만 트랜잭션 바깥에서 제한 재시도하는 얇은 facade와, 각 시도마다 새 트랜잭션을 소유하는 service로 분리한다. 업무 충돌·입력 오류·일반 무결성 오류는 재시도하지 않는다.
- 생성 명령은 버킷 ID·정책 버전·만료 시각을 입력받지 않는다. 서버가 사용자 입력인 매장·업무 날짜·시작 시각·offset·인원을 정규화하고, 인증된 소비자 계약에서 연락 대상 참조와 시간·취소 정책을 해석한 뒤 최신 수용량 정책에서 `[startAt, occupancyEndAt)` 전체를 연속해서 덮는 버킷을 결정한다. 서버가 해석한 연락 대상은 생성 요청 지문에 넣지 않고 거래 스냅샷으로만 보존한다.
- 생성 replay는 신규 거래 자격보다 먼저 판정한다. replay가 아닌 새 Hold 생성과 수용량 정책 재게시는 기존 Store 공개 계약으로 같은 Store 행을 먼저 잠가 직렬화한다. 종결은 매장의 CLOSED·예약 기능 비활성 여부와 무관하게 Hold 행을 먼저 잠그고 계속한다. 정책 재게시는 확정 Reservation과 보호 상태 Hold를 각각 PK 오름차순으로 잠근 뒤 관련 수용량 버킷으로 진행하며, 모든 경로는 aggregate-before-bucket과 버킷 PK 오름차순을 지킨다.
- 생성은 모든 관련 버킷에서 양의 전체 인원과 팀 1건을 확보한 뒤 `ReservationHold`, 구간별 allocation, `null → ACTIVE` 생성 감사, `expiresAt - 2분` 경고 의무를 한 트랜잭션에 기록한다. 구간 누락·수용량 부족·저장 실패가 하나라도 있으면 점유를 포함해 전부 롤백한다.
- `(consumerAccountId, creationCommandId)`가 이미 존재하면 최초 요청에서 사용자가 통제한 정규 입력 의미를 영속 스냅샷과 비교한다. 같은 의미면 정책 재게시 여부와 관계없이 현재 Hold 결과를 replay하고, 다른 의미면 `COMMON_007`로 거절한다. 생략한 offset과 서버가 해석한 값과 같은 명시 offset은 같은 의미이며, 파생된 현재 정책 버전·현재 버킷 구성은 비교 지문에 넣지 않는다.
- 생성 replay 판정 뒤 같은 소비자·매장·겹치는 서비스 구간의 `ACTIVE`, `RECONCILIATION_REQUIRED`, `CONFIRMED` Hold와 확정 Reservation을 잠금 조회한다. 기존 유효 거래가 있으면 새 선점을 만들지 않고 `RESERVATION_004`로 거절하며 `RELEASED`, `EXPIRED` Hold는 중복 후보에서 제외한다.
- 생성은 `uk_reservation_holds_creation_command`, 종결은 `uk_reservation_hold_transition_audits_command` 충돌만 식별해 실패한 트랜잭션이 끝난 뒤 새 트랜잭션에서 기존 결과를 조회한다. 같은 명령 의미면 replay하고 다른 의미면 `COMMON_007`로 거절하며, 그 밖의 unique·FK·CHECK 위반은 replay로 숨기지 않는다.
- 일반 종결은 `ACTIVE → CONFIRMED|RELEASED|EXPIRED|RECONCILIATION_REQUIRED`만 허용한다. 대사 복구는 `RECONCILIATION_REQUIRED → CONFIRMED|RELEASED`만 허용하며 그 밖의 전이는 `RESERVATION_005`로 거절한다.
- #265는 검증된 목표 상태를 적용하는 수용량 전이 명령, 정확히 한 번의 점유 유지·반환, append-only 전이 감사와 replay 판정만 소유한다. 결과 불명확 여부나 금전 결과를 스스로 판단하지 않는다. #267은 #265·#266 명령을 worker·명령 시점 만료·그룹 경합에서 호출하는 runtime을 소유하고, #238은 Payment/PG 원본을 검증해 적용할 목표 상태를 결정한다.
- `CONFIRMED`와 `RECONCILIATION_REQUIRED`는 수용량 점유를 유지한다. `RELEASED`와 `EXPIRED`만 allocation의 인원·팀을 정확히 한 번 반환하며, 상태 전이·수용량 변경·감사 기록은 함께 커밋한다.
- #265의 만료 판정은 명시적 EXPIRED 명령에만 적용한다. 중앙 `Clock`에서 `now < expiresAt`이면 거절하고 `now >= expiresAt`이면 단일 만료를 허용한다. 확정·해제·대사 명령 진입 시 만료를 우선하는 지연 만료와 scheduler/worker 자동 만료는 #267이 소유한다.
- 종결 명령은 상위 서버 조정자가 발급하고 재전송에서도 재사용하는 전역 고유 operation ID를 사용한다. 감사에는 raw 클라이언트 키가 아니라 이 내부 ID를 저장하며, 같은 ID·같은 Hold·같은 목표 상태 replay는 추가 전이·감사·수용량 반환 없이 현재 최신 Hold 결과를 반환한다. 같은 ID를 다른 Hold나 다른 목표 상태에 재사용하면 `COMMON_007`로 거절한다.
- 수용량 정책 재게시는 잠근 aggregate의 최신 상태를 기준으로 기존 확정 Reservation 점유와 아직 최종 Reservation으로 전환되지 않은 `ACTIVE`, `RECONCILIATION_REQUIRED`, `CONFIRMED` Hold 점유를 각각 한 번만 새 정책 버킷에 합산한다. 예약 취소·Hold 종결과 경합해도 잠금 뒤 확정된 상태만 이월한다. `RELEASED`, `EXPIRED` Hold는 이월하지 않으며, 분할·병합된 새 버킷에서도 각 Hold의 전체 겹침 구간에 인원과 팀 1건을 반영한다. #238의 최종 전환은 점유 소유권을 원자적으로 이전해 같은 거래의 Hold와 Reservation을 동시에 계산하지 않는다.
- 정책 재게시 뒤 Hold를 해제하거나 명시적으로 만료할 때는 최초 allocation 버킷과 현재 최신 정책의 겹치는 버킷을 합친 PK 정렬 집합을 잠그고 각 버킷에서 한 번만 복구한다. 같은 ID는 중복 제거하고 과거 중간 정책 버킷은 감사용 이력으로 남겨 수정하지 않는다.
- Hold 부재는 `RESERVATION_001`, 수용량 부족은 `RESERVATION_003`, 중복 유효 거래는 `RESERVATION_004`, 허용되지 않은 전이는 `RESERVATION_005`, allocation·최신 버킷 불일치는 `RESERVATION_008`, 인원 정책 위반은 `RESERVATION_009`, 멱등 재사용은 `COMMON_007`, 기술적 잠금 재시도 소진은 `COMMON_008`을 사용하며 #265에서 새 공개 오류 코드를 추가하지 않는다.

### 임시 선점 그룹의 메뉴 수량 원자 결합

> 활성화 단계: Issue #266 — 수용량과 선택 메뉴 수량의 단일 그룹 primitive만 활성, HTTP·worker·Payment·최종 Reservation 생성 비활성

- 생성 명령의 선택 메뉴는 `menuId`별로 중복 수량을 합산하고 메뉴 ID 오름차순으로 정규화한다. 메뉴별 합산 overflow와 허용 수량 범위 위반은 저장·잠금 전에 거절한다. 빈 목록은 MenuHold 행 부재라는 하나의 정규 의미를 가진다.
- 생성 replay는 기존 수용량 입력 의미와 함께 임시 MenuHold에 저장된 메뉴 ID·합산 수량을 비교한다. 메뉴 있음/없음 변경, 메뉴 ID 변경 또는 합산 수량 변경은 `COMMON_007`이며 Hold·수용량·메뉴 재고·감사를 변경하지 않는다. 최초 replay와 Store 잠금 뒤 concurrent replay가 같은 비교를 사용한다.
- fresh 생성은 Store와 겹치는 Reservation·Hold, 수용량 버킷을 기존 순서로 잠그고 수용량을 점유한 뒤 `ReservationHold`를 영속한다. 선택 메뉴가 있으면 예약 소유 `ReservationTemporaryMenuHoldPort`를 호출해 메뉴 재고 버킷을 PK 오름차순으로 잠그고 수량 원장과 임시 MenuHold를 같은 트랜잭션에 기록한다. 일부 메뉴 부족, 계약 불일치 또는 저장 실패에는 ReservationHold·allocation·수용량·메뉴 재고·감사·경고 의무를 전부 롤백한다.
- 메뉴 재고 확보 operation ID는 소비자 범위 생성 command ID를 재사용하지 않는다. 영속된 Hold ID로 `reservation-temp-menu-acquire:{reservationHoldId}` 형식의 100자 이하 결정적 전역 ID를 만들고 MenuHold의 case-sensitive unique 계약을 따른다.
- 종결은 ReservationHold를 잠근 직후 포트의 `lockForTransition(reservationHoldId)`로 임시 MenuHold 루트만 잠근다. 이후 수용량 유지 또는 복구를 처리하고, 포트의 `applyTransition`이 MenuHold 상태 변경과 필요한 메뉴 재고 복구를 수행한다. 두 계약은 호출자 트랜잭션에 필수 참여하며 최종 잠금 순서는 `ReservationHold → temporary MenuHold → capacity bucket PK → inventory bucket PK`다.
- 생성과 종결의 MenuHold 위치는 의도적으로 다르다. fresh 생성은 기존 MenuHold 행을 잠그지 않고 `capacity → 새 MenuHold insert → inventory`로 진행하며, 종결만 기존 MenuHold 루트를 `capacity`보다 먼저 잠근다. 두 경로의 공통 불변식은 `capacity`가 항상 `inventory`보다 앞서고 각 버킷을 PK 오름차순으로 잠근다는 것이다.
- `ACTIVE → RECONCILIATION_REQUIRED`는 수용량과 메뉴 수량을 모두 유지한다. `ACTIVE|RECONCILIATION_REQUIRED → RELEASED`와 `ACTIVE → EXPIRED`는 수용량과 메뉴 수량을 같은 트랜잭션에서 한 번만 반환한다. `CONFIRMED`는 재고를 유지하며 임시 MenuHold에 기존 최종 Reservation ID를 연결한다.
- 메뉴가 있는 `CONFIRMED` 명령만 양의 `finalReservationId`를 요구한다. 메뉴 없는 그룹과 다른 목표 상태에는 이 값이 없어야 한다. 최초 audit replay와 Hold 잠금 후 concurrent replay도 임시 MenuHold의 영속 `reservationId`를 비교하며, 같은 operation ID를 다른 최종 Reservation에 재사용하면 `COMMON_007`이다.
- #266은 호출자가 검증한 목표 상태와 이미 존재하는 최종 Reservation ID를 그룹에 적용하는 primitive만 소유한다. 자동 만료·명령 시점 만료 우선·중복 worker·대사 orchestration은 #267, Payment/PG 검증·목표 상태 결정·최종 Reservation 생성은 #238이 소유한다.

### 임시 선점 만료·대사 runtime

> 활성화 단계: Issue #267 — 중앙 만료 worker, 명령 시점 만료 우선, 대사 수렴과 장기 체류 관측 활성

- 자동 만료 후보는 조회 시각 이하로 `expiresAt`이 지난 `ACTIVE` Hold ID다. 조회는 무잠금 힌트로만 사용하고 한 poll 안에서 `reservationHoldId` keyset pagination으로 전진한다. 각 후보의 최종 상태와 만료 경계는 Hold 행을 잠근 뒤 중앙 `Clock`으로 다시 판정하며, 한 후보의 실패가 다음 후보를 막지 않는다. 종료 interrupt를 관찰하면 남은 batch 처리를 중단한다.
- job과 후보 조회·조정 service는 트랜잭션을 열지 않는다. 각 후보는 기존 `Propagation.NEVER` command facade를 거쳐 `ReservationHoldService.transition`의 새 트랜잭션 하나에서 처리한다. worker 또는 조정 service에 outer transaction을 두거나 같은 객체의 self-invocation으로 이 경계를 우회하지 않는다.
- 자동 만료는 `SYSTEM` actor와 null actor ID, `reservation-hold-expire:{reservationHoldId}` operation ID, Hold의 영속 `expiresAt`을 `requestedAt`으로 사용한다. 이 값은 worker 재시작·중복 실행·명령 시점 만료에서 모두 같아야 하며 `reservation-hold-expire:` namespace는 `EXPIRED` 내부 명령 전용이다. 실제 잠금 뒤 판정·감사 발생 시각은 한 번 읽은 중앙 `Clock`의 값을 `occurredAt`으로 사용한다.
- 모든 확정·해제·대사 명령은 기존 operation replay 판정을 먼저 수행하고 Hold와 임시 MenuHold를 기존 순서로 잠근다. replay가 아니고 `ACTIVE && now >= expiresAt`이면 원 요청의 operation ID를 소비하거나 EXPIRED 감사에 기록하지 않고, 위 결정적 만료 명령으로 치환해 기존 수용량·메뉴 수량 복구와 상태 전이·감사를 한 번 수행한 뒤 현재 `EXPIRED` 결과를 반환한다. 만료 때문에 실행되지 않은 원 operation ID의 재사용뿐 아니라 이미 `EXPIRED`인 Hold에 도착한 새 명령도 operation ID를 소비하거나 별도 감사를 추가하지 않는 no-op으로 현재 `EXPIRED` 결과를 반환한다. 호출자는 command의 목표 상태가 적용됐다고 추정하지 않고 반환된 Hold 상태를 최종 판단 근거로 사용한다.
- `now < expiresAt`인 `ACTIVE`만 원래 확정·해제·대사 목표를 적용할 수 있다. `RECONCILIATION_REQUIRED`에는 자동 만료 치환을 적용하지 않고, 검증된 `CONFIRMED|RELEASED` 대사 명령만 기존 primitive로 실행한다. Payment/PG 원본 조회, 금전 결과 판정과 최종 Reservation 생성은 #238이 소유한다. #238 조정자는 만료에 밀린 원 operation의 감사 존재를 전제로 삼지 않고, 영속 process·Payment 결과와 Hold의 현재 상태 및 결정적 `SYSTEM` 만료 감사를 대조해 확정 또는 전액 환불로 수렴해야 한다.
- 잠금 순서는 `ReservationHold → temporary MenuHold → capacity bucket PK → inventory bucket PK`를 유지한다. 후보 조회와 장기 체류 관측은 잠금 순서나 다른 Schedule·Payment·Waiting·Notification 도메인의 entity, repository 또는 API 계약을 추가하지 않는다.
- `RECONCILIATION_REQUIRED` 장기 체류 기준 시각은 해당 상태로 전이한 append-only 감사의 `occurredAt`이며 경계는 정확히 10분이다. `now < occurredAt + 10분`은 대상이 아니고 `now >= occurredAt + 10분`부터 현재도 `RECONCILIATION_REQUIRED`인 그룹을 관측한다. 이 관측은 count만 포함한 `event=reservation_hold_reconciliation_stalled` 로그를 남기고 Hold·계정·매장·명령 식별자나 연락처를 기록하지 않는다.
- CloudWatch Logs metric filter는 위 이벤트를 `ReservationHoldReconciliationStalled` 지표로 바꾸고 5분 합계가 0보다 크면 기존 staging SNS 주제의 알람을 활성화한다. 관측은 현재 위험 상태가 지속되는 동안 반복되는 level-triggered 신호이며 별도 발송 receipt를 영속하지 않는다. 중복 poll은 상태·수용량·메뉴 수량·감사를 변경하지 않고, 장기 체류가 사라져 이벤트가 없으면 `notBreaching`으로 복귀한다.
- 만료와 장기 체류 관측은 이름이 지정된 전용 single-thread scheduler를 명시적으로 사용하고 scheduler bean은 다른 `@Scheduled` 작업의 기본 후보가 아니다. enabled, poll delay와 batch size는 양수 검증 가능한 운영 설정이며 기본 활성화하되 `enabled=false`로 중지할 수 있다. 구체 기본값은 구현 선택이지 제품 만료 정책이 아니다.
- 선점별 `expiresAt - 2분` 경고 의무는 기존 unique 영속 계약을 유지한다. 실제 Notification 발송·채널·provider·재시도 부재 또는 실패는 만료 worker와 장기 체류 관측을 막지 않는다.

## 예약금 결제 후 최종 확정

> 활성화 단계: Issue #238 — 승인된 exact allowlist의 runtime 활성

### 소유권과 선행 계약

- Reservation이 예약금 거래의 조정자다. 자원 상태의 원본은 ReservationHold·MenuHold이고 금전 상태와 환불 원장의 원본은 Payment다. `ReservationDepositProcess`는 두 상태를 복제하지 않고 조정 단계와 후속 작업 의무만 소유한다.
- Reservation은 Payment의 공개 `PaymentService`·`PaymentContracts`만 사용하고 Payment Entity·Repository 또는 PortOne 모델을 참조하지 않는다. Payment도 Reservation·ReservationHold·MenuHold를 역조회하거나 변경하지 않는다.
- 예약금 필요 여부와 Store 소유 입력·안정적인 정책 버전은 #313의 Store 공개 Service·DTO로 읽는다. 대표 메뉴 설정 버전, 각 메뉴 ID·게시 버전·기본 가격은 기존 `RepresentativeMenuQueryService.getCurrent(storeId)` 공개 결과를 재사용하며 Menu DTO를 영속 모델로 사용하지 않는다.
- #267은 만료 대상 탐색·임대·기술적 실행만 소유한다. 결제 process가 연결된 Hold를 직접 만료시키지 않고 #238 조정자를 호출한다. #238이 Payment 공개 DTO로 목표 상태를 결정하고 #265·#266 공개 명령이 Hold·MenuHold 전이를 적용한다.
- Migration은 목적과 충돌 없는 다음 번호 선택 규칙만 먼저 고정한다. 실제 Flyway 파일명은 migration 파일 생성 직전에 최신 `dev`와 열린 PR을 다시 확인해 Issue #238 exact allowlist에 추가하고, PR 진행 중 선행 migration이 먼저 병합되면 번호와 Issue·테스트·PR 본문을 함께 갱신한다.

### 생성과 공개 API

- 기존 `POST /api/v1/consumers/me/reservations`를 유지한다. 예약금 불필요 판정은 기존 `201 ReservationSuccessResponse`와 즉시 확정 의미를 보존한다.
- 예약금 필요 판정은 `202 ReservationRequestResponse`를 반환한다. 성공한 `202`에는 `reservationRequestId`, 조정 상태, Hold `expiresAt`과 `PaymentPreparation`이 반드시 있으며 nullable 준비 결과나 별도 브라우저용 Payment 준비 API를 만들지 않는다.
- 공개 `paymentPreparation`은 Payment가 요청 생성 시 반환한 값을 저장한 불변 snapshot이다. 생성 `202`부터 `COMPLETED`·`ABANDONED`·`EXPIRED`·보상·복구 상태의 최신 `GET`까지 required·non-nullable로 같은 값을 보존하며, snapshot의 `status=READY`와 `sourceExpiresAt`은 현재 Payment 상태나 현재 결제 가능 여부를 뜻하지 않는다.
- 같은 생성 URL을 참조하는 `mvp1-openapi.yaml`은 `components.pathItems.Mvp1ConsumerReservations` stage projection으로 기존 `201`·오류와 조회 계약만 노출하고, full consumer entrypoint만 예약금 `202`·전용 409를 함께 노출한다. 두 projection은 생성 타입에서 서로 덮어쓰지 않도록 서로 다른 `operationId`를 사용한다.
- 생성은 #266 정본의 `멱등 기록 → Store·중복 거래 직렬화 → 수용량 버킷 PK 오름차순 → ReservationHold·allocation·감사·경고 의무 → 새 임시 MenuHold root → 메뉴 재고 풀 PK 오름차순 → MenuHold 항목·수량 원장` 뒤 `ReservationDepositProcess·Payment 준비 원장·최초 202 결과`를 같은 MySQL 트랜잭션에 기록한다. Payment 준비는 PG 호출이 없는 내부 원장 생성이며 호출자 트랜잭션에 참여한다. 준비 유일 키 경합은 전체 rollback 뒤 facade가 새 트랜잭션으로 제한 재시도한다.
- Store·중복 거래 직렬화로 Store 행 잠금을 획득한 직후이자 첫 자원 쓰기 전에, 같은 caller transaction에서 `StoreReservationDepositPolicyQueryService.getCurrent(storeId)`를 먼저 호출하고 `RepresentativeMenuQueryService.getCurrent(storeId)`를 다음으로 호출한다. 두 조회는 독립 transaction을 열지 않으며 Store 정책 상태·비율·revision, 대표 메뉴 설정 version, 메뉴별 ID·게시 version·기본 가격과 예약 인원수를 정해진 관찰 순서 그대로 하나의 immutable 계산 snapshot에 저장한다. runtime 통합 테스트는 Store 조회의 `MANDATORY` 참여, Store→Menu 호출 순서, 독립 transaction 0건과 snapshot 영속을 함께 검증한다.
- 같은 생성 멱등 키와 같은 요청 지문은 상태가 바뀌어도 저장된 최초 HTTP 상태와 최초 payload를 replay한다. 최신 상태는 `GET /api/v1/consumers/me/reservation-requests/{reservationRequestId}`에서 `reservationRequestId + authenticatedConsumerAccountId`로 한 번에 조회하고 실제 부재와 타인 소유를 `RESERVATION_001`로 숨긴다.
- `POST .../{reservationRequestId}/finalizations`는 `Idempotency-Key`를 요구하며 브라우저 성공 주장 대신 저장된 `paymentId + consumerAccountId`로 `PaymentService.getOwnedPayment(...)`만 호출한다. 완료하면 확정 Reservation을 반환하고 미종결이면 `202`를 반환한다. 사용자가 호출하지 않아도 worker가 같은 내부 조정 명령을 실행한다.
- `POST .../{reservationRequestId}/abandonments`는 인증된 본인의 포기 의사와 `Idempotency-Key`만 받는다. 명령이 `COMPLETED` 전에 process를 잠그면 Payment가 이미 `PAID`이거나 이후 `PAID`로 확인돼도 Reservation을 만들지 않고 전액 환불 obligation으로 수렴한다. `READY`·명시적 비성공이면 Hold·MenuHold를 기존 명령으로 `RELEASED`하고 `ABANDONED`로 종결하며, `CONFIRMING`·`RECONCILIATION_REQUIRED`이면 포기 의사를 영속화하고 결과를 추측하지 않은 채 자원을 보호한다. 이미 `COMPLETED`인 거래만 `RESERVATION_005`로 거절하고 확정 Reservation 취소 정책으로 분리한다.
- 공개 `ReservationRequest.abandonmentRequested`는 단순 HTTP 요청 수신 여부가 아니라 포기 command가 `COMPLETED` 전에 process 잠금을 획득하고 포기 의도를 성공적으로 영속화했는지를 나타낸다. 영속 전에는 `false`, 영속 후에는 payment-driven 조정 상태와 독립적으로 `true`를 유지하며 `ABANDONED`·보상·복구 상태에서도 다시 `false`가 되지 않는다. 이미 `COMPLETED`라 거절된 command와 포기 의도 없이 시작된 보상·복구는 이 값을 변경하지 않는다.
- 미종결 생성·finalization·abandonment 명령만 `202`를 사용한다. 최신 상태 `GET`은 `200`, 생성 replay는 최초 응답, 명시적 실패는 계약된 오류를 유지한다.

### 조정 상태와 판정

- 조정 상태는 `AWAITING_PAYMENT`, `FINALIZING_RESOURCES`, `COMPLETED`, `ABANDONED`, `EXPIRED`, `COMPENSATION_REQUIRED`, `COMPENSATING`, `COMPENSATED`, `RECOVERY_REQUIRED`다.
- Payment의 `READY + lastAttemptStatus=FAILED|CANCELLED`은 원래 만료 시각까지 재결제 가능한 결과이므로 포기 의사가 없으면 `AWAITING_PAYMENT`를 유지한다. 일반 `ACTIVE` 선점은 중앙 `now >= expiresAt`이면 만료·반환하고 뒤늦은 `PAID`는 전액 환불한다.
- 만료 전에 Payment가 `CONFIRMING`·`RECONCILIATION_REQUIRED`로 관측된 거래만 Hold·MenuHold를 `RECONCILIATION_REQUIRED`로 전이해 점유를 보호한다. 이 보호 예외는 일반 `ACTIVE` 선점의 만료를 연장하지 않으며 Payment 결과를 성공·실패로 추측하거나 자원을 재판매하지 않는다.
- 보호 예외에서는 Payment가 서버에서 검증하고 원장에 기록한 `paidAt < expiresAt`이고 포기 의사가 없으며 자원이 계속 보호 중이면 벽시계 만료 후 대사돼도 최종 확정한다. `paidAt >= expiresAt`, 이미 반환된 자원 또는 영속 포기 의사가 있으면 Reservation을 만들거나 되살리지 않고 전액 환불로 수렴한다.
- 최종 확정은 `멱등 기록 → ReservationDepositProcess → ReservationHold·MenuHold aggregate → 수용량 버킷 PK 오름차순 → 필요한 메뉴 재고 풀 PK 오름차순`의 상대 순서를 지킨다. 최종 Reservation 생성, 선택 MenuHold 확정, Hold 점유의 Reservation 점유 이전과 process 완료를 같은 트랜잭션에 기록하여 같은 거래의 Hold와 Reservation을 동시에 산입하지 않는다.
- #267은 process 연결 여부를 읽기 전용으로 식별하고 ReservationHold·MenuHold·bucket 잠금을 보유하지 않은 채 #238 조정자에게 위임한다. #238은 `ReservationDepositProcess → ReservationHold·MenuHold aggregate → 수용량 bucket → 메뉴 재고 pool` 순서를 지키며 Hold를 먼저 잠근 뒤 process를 잠그는 역순 호출을 금지한다.
- 포기·결제 확인·만료·worker가 경합하면 process와 Hold의 상태 버전·멱등 operation ID·worker fencing으로 하나의 다음 결과만 유효하다. 포기 의사가 기록된 process는 이미 확인됐거나 뒤늦게 확인된 `PAID`로 최종 확정 경로에 복귀하지 않는다.

### 영속 스냅샷과 환불 의무

- `ReservationDepositProcess`는 ReservationHold와 1:1, 최종 Reservation과 0..1로 연결한다. Payment 공개 `paymentId`는 0..1 불변·유일 참조로 저장하되 Payment 테이블 물리 FK를 만들지 않는다. 공개 ID 조회 실패에는 연결을 삭제하지 않고 `RECOVERY_REQUIRED`를 유지한다.
- process는 조정 상태·상태 버전, worker 임대·fencing·다음 실행 시각, 확정·포기·환불의 안정적인 내부 operation ID, 생성·갱신·종결·포기 요청 시각을 보존하며 종결 뒤 삭제하지 않는다.
- process·refund scheduled worker와 전용 scheduler는 `miriyum.reservation.deposit-worker.enabled=true`를 명시한 환경에서만 생성한다. property 누락과 `false`에서는 비동기 신규 claim을 시작하지 않으며 배포 환경의 명시적 활성화·중단 배선은 #404가 소유한다.
- 예약금 계산 근거는 Store 설정 revision·비율·통화·예약 인원·계산 정책 버전·계산된 최종 예약금·대표 메뉴 설정 version·대표 메뉴 가격 합계·대표 메뉴 수와 계산에 사용한 각 대표 메뉴 ID·게시 version·기본 가격을 불변 snapshot으로 보존한다. 공개 DTO의 값만 복사하고 이후 Store 설정이나 Menu 원본 변경으로 갱신하지 않는다.
- 결제 성공 뒤 자원 확정 불가 또는 포기·반환 뒤 늦은 `PAID`는 process와 같은 트랜잭션에 정확히 한 건의 `ReservationDepositRefundObligation`을 기록한다. obligation은 process·불변 원인 사건, `paymentId`, 전액·통화·환불 정책 버전, 안정적인 Payment 환불 멱등 키와 작업 임대·재시도 정보만 소유한다. process와 원인 사건 조합뿐 아니라 `process + paymentId + FULL_DEPOSIT_COMPENSATION` 조합에 최대 한 obligation만 허용하며 포기·만료·자원 확정 실패·late PAID의 여러 원인 사건은 같은 obligation의 append-only 감사 근거로 연결한다.
- 커밋 뒤 `PaymentService.requestRefund(...)`를 호출한다. Payment 환불 원장이 실제 금전 결과의 유일한 원본이고 obligation은 요청 의무만 나타낸다. `REQUESTED`·`VALIDATING`·`PROCESSING`, `COMMON_008`·`COMMON_012` `ServiceException`, 그 밖의 일시적 기술 예외만 같은 의무로 재대기한다. 동일 멱등 키의 명시적 `FAILED`, 결과 불명확, 그 밖의 Payment 업무 `ServiceException`은 새 환불을 만들지 않고 obligation `RECONCILIATION_REQUIRED`, process `RECOVERY_REQUIRED`로 격리해 운영 대사를 기다린다.

### 오류와 검증 경계

- 예약금 요청 부재·타인 소유는 `RESERVATION_001`, 허용되지 않은 조정 전이는 `RESERVATION_005`, 멱등 키의 다른 요청 지문은 `COMMON_007`, 기술적 잠금 재시도 소진은 `COMMON_008`을 사용한다.
- Store·Menu·Payment 오류는 각 Reservation route가 실제 호출하는 공개 Service에서 발생 가능한 소유 오류만 OpenAPI에 열거한다. 이미 영속된 `paymentId`를 `getOwnedPayment`에서 찾지 못한 `PAYMENT_001`은 소비자 404로 전파하지 않고 process를 `RECOVERY_REQUIRED`로 전이해 현재 ReservationRequest 상태로 반환한다. Payment 이력 cursor·Webhook 전용 오류를 Reservation API에 노출하지 않으며 비동기 환불·대사 실패를 즉시 소비자 오류로 위장하지 않는다.
- 기존 거래와 Payment는 backfill하지 않는다. runtime 구현은 snapshot·본인 소유·멱등 API 계약, 실제 MySQL 단일 commit/rollback, 결제·Webhook·worker 중복, 확정/만료/포기 경합, 결과 불명확 보호, 환불 obligation, 다중 worker 임대·fencing과 실제 migration 제약을 검증한다.

## 수용량

- 자원은 개별 테이블·좌석이 아니라 매장·업무 날짜·시간 구간별 전체 예약 가능 인원과 팀 수다.
- `[startAt, occupancyEndAt)`과 겹치는 모든 버킷에서 전체 동행 인원과 팀 1건을 함께 확보한다.
- 어느 버킷에서든 인원 또는 팀 수가 부족하면 전체 예약이 실패한다.
- 매장 운영자는 날짜별 버킷의 `maxPeople`, `maxTeams`, `minPartySize`, `maxPartySize`, `infantsAllowed`를 게시한다.
- `maxPartySize`는 `maxPeople`보다 클 수 없다.
- 새 설정은 신규 예약에 적용하며 기존 확정 예약과 배정 스냅샷을 축소·취소하지 않는다.
- 기존 점유보다 작은 한도를 게시하면 기존 예약은 유지하고 신규 가용량만 0으로 계산한다.

### 검색용 부분 가용성 공개 계약

- 검색 도메인은 Reservation entity·repository를 직접 조회하지 않고 `ReservationSearchAvailabilityService.getAvailabilities(List<Long>, ReservationSearchAvailabilityCondition)` 공개 읽기 계약만 사용한다.
- `serviceDate`는 필수다. 날짜가 없는 검색은 이 계약을 호출하지 않으며 Reservation 가용성으로 검색 결과를 제한하지 않는다.
- `startTime`과 `partySize`는 서로 독립적인 선택 조건이다. 시간이 있으면 기존 매장별 시간 해석 계약으로 실제 `[startAt, occupancyEndAt)`을 계산하고, 시간이 없으면 해당 날짜·매장 최신 수용량 정책의 버킷 시작 시각을 후보로 삼아 현재 Store 예약 접수 일정과 Reservation 시간 정책을 통과하는 후보가 하나라도 있는지 판정한다. 시간을 생략했다고 임의의 기본 시각을 넣지 않는다.
- `partySize`가 있으면 전체 점유 구간의 모든 버킷이 그 인원과 영유아 조건을 수용해야 한다. 인원이 없으면 1명으로 간주하지 않고 모든 점유 버킷의 `minPartySize`, `maxPartySize`, 잔여 인원, 잔여 팀, `infantsAllowed`를 함께 만족하는 양의 공통 인원이 하나라도 있어야 한다.
- 날짜별 최신 수용량 버킷은 매장 후보 전체에 대해 한 번에 조회하고, 시간이 없는 후보 해석은 같은 시작 시각의 매장을 묶어 호출한다. 매장별 repository 반복 조회를 허용하지 않는다.
- 결과는 입력 매장 ID의 순서·개수·중복을 그대로 보존한다. 시간 해석 또는 수용량 조회 응답의 순서·매장·날짜·개수가 계약과 다르거나, 구간 공백·겹침·정책 버전 혼합·자정 넘김·DST 모호성이 있으면 가용성을 추측하지 않고 해당 배치를 `UNAVAILABLE`로 실패 폐쇄한다.
- 이 읽기 결과는 예약 생성 성공을 보장하지 않는다. 실제 생성 트랜잭션은 현재 일정·시간 정책·수용량을 다시 잠그고 검증한다.

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
- 1·2차 MVP의 비금전 V1(양수 BIGINT `1`)은 서버 중앙에서 한 번 얻은 `requestedAt: Instant`와 예약에 저장된 `cancellationPolicyVersion=1`으로 판정한다. 클라이언트 시각·입력 version·현재 version 조회 또는 fallback을 사용하지 않는다.
- 소유권 또는 매장 관리 권한과 `CONFIRMED` 조건을 통과한 `CONSUMER`·`STORE_OPERATOR`는 `requestedAt`이 `startAt` 전·정각·후라는 이유만으로 추가 거절되지 않는다. V1에는 시간 구간·cutoff가 없다.
- null·unknown `cancellationPolicyVersion`은 V1 또는 현재 version으로 fallback하지 않으며 허용 결과를 만들지 않는다. 기존 예약은 저장된 version 의미로 판정하고 근거 없는 V1 backfill이나 소급 재해석을 하지 않는다.
- 1차 MVP에는 결제가 없고 V1은 비금전이므로 환불 비율·금액·시간 구간·cutoff·`PAY-*` 참조를 응답하거나 판정에 포함하지 않는다. 이 항목은 고도화에서 별도 정책 버전으로 확정한다.
- #51은 권한·소유 확인, 상태 전이·자원 복구, 멱등·감사와 기존 공개 오류 매핑을 조정한다. 본 명세는 V1 판정 계약만 정하며 새 API·상태·외부 오류·결제 동작을 추가하지 않는다.
- 예약 상태 `CANCELLED`, 모든 수용량 배정 해제, 연결 메뉴 홀드 `RELEASED`와 메뉴 수량 복구를 한 트랜잭션에서 확정한다.
- 같은 멱등 키·지문의 재시도는 수량을 다시 복구하지 않고 기존 취소 결과를 반환한다.
- `CANCELLED`, `FULFILLED`에서 새로운 취소 명령을 실행할 수 없다.
- 취소 주체는 `CONSUMER` 또는 `STORE_OPERATOR` 사건 필드로 기록하며 상태 enum을 늘리지 않는다.

### 고도화 예약금 취소 V2

- 예약금 필요 신규 Hold는 `cancellationPolicyVersion=2`를 저장하고 final Reservation이 그대로 승계한다. 비예약금 직접 Reservation은 V1이며 기존 V1/null/unknown을 V2로 backfill·fallback·소급 해석하지 않는다.
- evaluator 입력은 저장 version, `responsibilityCode`, `confirmedAt=Reservation.createdAt`, `startAt`, command facade가 한 번 얻은 `requestedAt`이다. Store·Platform 귀책은 10000 bps다. Consumer는 `requestedAt <= confirmedAt + 10분 && requestedAt < startAt` 또는 `requestedAt <= startAt - 48시간`이면 10000, `startAt - 48시간 < requestedAt <= startAt - 24시간`이면 5000, 그 뒤면 0 bps다.
- V2 취소는 멱등 claim과 Reservation 잠금 뒤 `finalReservationId`로 예약금 process scalar link를 비잠금 조회한다. `COMPLETED`, 같은 final Reservation, 양수 문자열 `paymentId`가 아니면 실패 폐쇄한다. process를 잠그거나 변경하지 않는다.
- `CANCELLED`, 수용량·MenuHold 수량 복구, 취소 감사, stable UUID와 Payment scalar를 가진 `ReservationDepositDispositionObligation(PENDING)`은 같은 transaction에서 commit한다. 이 transaction 안에서는 Payment를 호출하지 않는다.
- obligation 상태는 `PENDING | PROCESSING | COMPLETED | RECONCILIATION_REQUIRED | RECOVERY_REQUIRED`, operation은 `APPLY | QUERY`다. claim과 결과 반영은 각각 `REQUIRES_NEW` 짧은 transaction이며 외부 Payment 공개 Service 호출은 그 사이 transaction 밖에서 실행한다. 결과 반영은 obligation과 fencing token만 잠그고 stale 결과를 무시한다.
- `RECONCILIATION_REQUIRED`에서는 Payment 기존 source 결과를 `QUERY`만 하며 실패 후에도 `QUERY`를 유지한다. Payment 환불 처리 임대 안의 `PROCESSING` poll은 provider 대사 시도 횟수를 소비하지 않는다. 임대 만료 뒤 결과 불명 조회만 유한 재시도 한도를 소비하며, 새 apply·refund를 만들지 않고 한도 소진 시 `RECOVERY_REQUIRED`로 보낸다.
- 최초 cancellation POST와 같은 key·지문 replay는 저장된 최초 projection을 반환한다. Reservation detail GET은 최신 obligation projection만 읽고 Payment를 동기 호출하지 않는다. V1/null/unknown은 `depositDisposition: null`이다.
- scheduler·worker는 `miriyum.reservation.deposit-worker.enabled=true`일 때만 등록되며 누락·false에서는 비활성이다.

## 방문 완료·회전형 QR·노쇼

### 기존 직접 방문 완료 보존

- 활성 매장 운영자의 현재 대표 소유권을 fresh와 replay에서 확인한다. CLOSED·휴점은 신규 거래만 차단하며 이미 `CONFIRMED`인 예약의 방문 완료는 허용한다.
- `POST /api/v1/store-operators/stores/{storeId}/reservations/{reservationId}/fulfillments`는 QR·시간·노쇼와 독립된 기존 보조 명령으로 유지한다. Reservation과 연결 MenuHold, `reservation_fulfillment_audits` 성공 감사, 멱등 성공 결과는 한 트랜잭션에서 모두 commit하거나 rollback한다.
- 유효한 매장 운영자만 대상 매장의 `CONFIRMED` 예약을 `FULFILLED`로 전이하며 연결 MenuHold도 `FULFILLED`로 종결한다.
- V2 예약금 Reservation은 완료된 동일 final Reservation의 process scalar link를 먼저 검증하고 `RESERVATION_FULFILLED`, `CONSUMER`, 10000 bps PENDING obligation을 상태·감사와 같은 transaction에 저장한다. V1/null/unknown과 비예약금 예약은 기존 동작을 유지한다.

### QR grant 발급·회전

- 일반 사용자는 `POST /api/v1/consumers/me/reservations/{reservationId}/check-in-qr-grants`로 본인 `CONFIRMED` 예약의 grant를 발급한다. request body와 `Idempotency-Key`는 없고 성공은 `201`이다.
- 응답은 `reservationId`, raw `qrToken`, 1 이상의 `tokenVersion`, `issuedAt`, `expiresAt`을 포함한다. 이 credential-mint 응답만 raw token을 반환할 수 있다.
- token은 JWT가 아닌 server-stored opaque credential이다. `SecureRandom` CSPRNG 32바이트를 base64url padding 없이 인코딩하고 `rqg_v1_` prefix를 붙인다. 서버에는 전체 raw token의 SHA-256 digest만 저장한다.
- TTL은 정확히 30초이고 유효 구간은 `[issuedAt, expiresAt)`이다. 예약별 current grant 행 하나에서 `tokenVersion`을 1부터 단조 증가시키며 rotation overlap은 없다.
- 병렬 발급은 `Reservation → current grant` 잠금 순서로 직렬화한다. 두 요청이 모두 성공할 수 있지만 잠금 뒤 가장 큰 version만 유효하며 클라이언트도 가장 큰 version만 사용한다.
- 발급 facade는 transaction 밖에서 raw token/digest를 만들고 Auth `ConsumerQrEpochService.captureCurrent(consumerAccountId)`를 호출한다. transaction 안에서는 잠근 Reservation의 본인 소유·`CONFIRMED` 상태를 다시 확인하고 snapshot·digest만 저장한다. 생성·Valkey 실패는 `COMMON_012`이며 raw token을 반환하지 않는다.

### 운영자 QR 스캔

- 운영자는 `POST /api/v1/store-operators/stores/{storeId}/reservation-check-ins`에 `Idempotency-Key`와 strict body `{ "qrToken": "rqg_v1_<43-char-base64url>" }`을 보낸다. unknown field는 거부하고 request fingerprint에는 raw token 대신 SHA-256 digest만 포함한다.
- scan window는 서버 `Clock` 기준 정확히 `[startAt, startAt + 5분)`이다. 시작은 포함하고 정확히 `+5분`부터 신규 QR 성공은 거부한다.
- fresh 순서는 `현재 operator/store authority → idempotency claim → digest preliminary lookup → Reservation FOR UPDATE → current grant FOR UPDATE → digest/current version/expiry/미소비 재검증 → Auth requireCurrent → 시간/상태 재검증 → MenuHold termination lock → Reservation FULFILLED → MenuHold fulfill → 기존 fulfillment audit + QR audit → grant consumed → 멱등 결과 → commit`이다.
- preliminary unlocked lookup은 잠글 Reservation ID를 찾기 위한 힌트일 뿐 권한·성공·변경 근거가 아니다. 두 잠금 뒤 digest·version·expiry·소비·계정·epoch·매장·시간·상태를 전부 다시 검증한다.
- Auth 검증은 잠근 Reservation의 `consumerAccountId`와 저장 `ConsumerQrEpochSnapshot`을 `ConsumerQrEpochService.requireCurrent`에 전달한다. stale·account mismatch는 현재 epoch를 노출하지 않는 `AUTH_017`, Valkey 장애는 `COMMON_012`다. logout epoch increment 전 current 판정은 성공할 수 있고 increment 뒤 판정은 실패하는 #305 linearization을 유지하며 분산 transaction은 만들지 않는다.
- 성공 replay는 현재 operator/store authority를 다시 확인한 뒤 저장 결과를 반환한다. raw QR·digest·expiry·epoch·시간·이미 바뀐 Reservation 상태는 다시 검증하지 않는다.
- 성공은 기존 `ReservationFulfillmentAudit` 한 건과 QR 전용 audit를 함께 기록한다. QR audit는 방식·tokenVersion·행위자·시각·전이 상태·commandId만 보존하고 raw token·digest·opaque epoch를 복제하지 않는다.
- V2 예약금 QR 성공은 직접 방문 완료와 같은 `RESERVATION_FULFILLED`, `CONSUMER`, 10000 bps PENDING obligation을 같은 transaction에 저장한다.

### 운영자 노쇼 확정

- 운영자는 `POST /api/v1/store-operators/stores/{storeId}/reservations/{reservationId}/no-shows`에 `Idempotency-Key`와 strict body `{ "reason": "<ReservationNoShowReason>" }`을 보낸다.
- reason은 `USER_CAUSE_CANDIDATE`, `STORE_CAUSE_CANDIDATE`, `PLATFORM_EXTERNAL_CAUSE_CANDIDATE`, `UNCLEAR` 중 하나이며 필수이고 기본값이 없다. Issue #241은 최종 `NO_SHOW` 전이 뒤 V2 예약금에만 승인된 금전 매핑을 적용한다.
- fresh 순서는 `현재 operator/store authority → idempotency claim → Reservation FOR UPDATE → CONFIRMED·now >= startAt+5분·필수 reason → MenuHold termination lock → Reservation NO_SHOW → MenuHold FORFEITED → no-show audit → 멱등 결과 → commit`이다.
- `FORFEITED`는 수량 무복구 종결이다. 수용량·allocation·메뉴 재고·수량·return ledger·transfer를 조회하거나 변경하지 않는다. V2는 `USER_CAUSE_CANDIDATE → CONSUMER/0`, `STORE_CAUSE_CANDIDATE → STORE_RESPONSIBLE/10000`, `PLATFORM_EXTERNAL_CAUSE_CANDIDATE → PLATFORM_RESPONSIBLE/10000` PENDING obligation을 저장하며 `UNCLEAR`는 저장하지 않는다.
- 방문 transaction은 Payment 공개 Service나 provider를 호출하지 않고 기존 #239 disposition worker가 후속 처리한다. obligation 저장 실패는 방문 상태·감사·MenuHold·멱등 결과와 함께 rollback한다.
- replay는 현재 operator/store authority만 다시 확인하고 저장된 성공 결과를 반환한다. 같은 키를 다른 reservation/store/reason에 사용하면 `COMMON_007`이다.

### 단일 종결 승자

- QR scan, no-show, 기존 직접 방문 완료, cancellation은 모두 Reservation 행을 먼저 잠그므로 하나의 종결 전이만 성공한다. QR/no-show는 이어서 #385의 같은 MenuHold termination lock을 사용한다.
- `CANCELLED`, `FULFILLED`, `NO_SHOW`는 종결 상태다. 재활성화하거나 다른 종결 상태로 바꾸지 않으며 후속 감사·grant 소비·MenuHold 전이 중 하나라도 실패하면 멱등 기록을 포함해 전부 rollback한다.
- `FULFILLED`와 `NO_SHOW`는 수용량·allocation·메뉴 재고·수량 원장을 조회하거나 복구하지 않으며 V2에서만 승인된 disposition obligation을 생성한다.

## 오류 코드

| 외부 코드 | HTTP | 의미 |
| --- | --- | --- |
| `RESERVATION_001` | 404 | 본인 또는 대상 매장 범위에서 예약을 찾을 수 없음 |
| `RESERVATION_002` | 409 | 요청 시간이 영업·예약 접수 구간에 없음 |
| `RESERVATION_003` | 409 | 모든 점유 구간의 인원·팀 수를 확보할 수 없음 |
| `RESERVATION_004` | 409 | 같은 사용자·매장에 겹치는 활성 예약이 있음 |
| `RESERVATION_005` | 409 | 현재 예약 상태에서 요청한 전이 불가 |
| `RESERVATION_006` | 409 | 저장된 취소 정책에서 취소 불가(공개 오류 매핑은 #51 소유이며 V1은 `startAt` 시간만으로 사용하지 않음) |
| `RESERVATION_007` | 409 | 조회 뒤 정책·수용량 버전이 변경됨 |
| `RESERVATION_008` | 409 | 수용량 설정이 시간대·현재 점유와 충돌 |
| `RESERVATION_009` | 409 | 요청 인원이 매장 최소·최대 정책을 벗어남 |
| `RESERVATION_010` | 409 | 대상 시간 정책 버전·상태·게시 유일성 때문에 lifecycle 명령 불가 |
| `RESERVATION_011` | 409 | 잘 형성된 QR이 부재·교체·만료·소비됐거나 잠금 뒤 current grant와 일치하지 않음 |
| `RESERVATION_012` | 409 | 현재 중앙 시각이 QR scan window 밖임 |
| `RESERVATION_013` | 409 | 아직 `startAt + 5분` 경계 전이라 노쇼를 확정할 수 없음 |

QR 패턴·필수값 등 Bean Validation 실패는 `COMMON_001`, 잘못된 JSON·타입·enum·unknown field는 `COMMON_002`, Auth epoch stale/mismatch는 `AUTH_017`, Valkey·credential 생성 실패는 `COMMON_012`다. 메뉴 자격·수량 부족은 `MENU_HOLD_###`, 매장 상태·소속은 `STORE_###` 오류를 그대로 사용한다. 다른 도메인 오류를 편의상 RESERVATION 코드로 변환하지 않는다.

## 트랜잭션·동시성·재시도

- 생성·취소·기존 방문 완료·QR 스캔·노쇼·수용량 게시·시간 정책 lifecycle 명령은 `Idempotency-Key`를 요구한다. QR grant 발급은 예외로, key 없이 매 호출 새 version으로 회전한다.
- 조정하는 `ReservationService`가 C-007의 5초 트랜잭션 경계와 `READ_COMMITTED`를 사용한다.
- 메뉴 홀드 생성·해제는 예약 소유 `ReservationMenuHoldPort`와 MenuHold 소유 `ReservationMenuHoldAdapter`를 통하며, MenuHold의 예약 시간 조회는 좁은 `ReservationTimeResolutionService`를 사용한다.
- 시간 정책 명령의 잠금 순서는 멱등 기록 → Store 행 → 대상 정책 → 현재 ACTIVE다.
- 잠금은 E-005에 따라 멱등 기록 → 예약 aggregate → 수용량 버킷 → 메뉴 재고 풀 순서와 각 PK 오름차순을 지킨다.
- 교착·일시 잠금·낙관 버전 충돌만 최초 실행 포함 최대 총 3회 새 트랜잭션으로 재시도한다. 첫 실패 뒤 100~200ms, 두 번째 실패 뒤 300~500ms 지터를 트랜잭션 밖에서 기다린다.
- 수용량·중복·상태·메뉴 재고 부족은 업무 결과이므로 자동 재시도하지 않는다.
- 최종 충돌은 `COMMON_008`을 사용하고 성공이나 자원 부족을 추측하지 않는다.
- H2·mock만으로 동시성 성공을 주장하지 않고 Testcontainers MySQL에서 마지막 수용량과 메뉴 수량 경합을 검증한다.
- QR/no-show의 MySQL 업무 transaction도 `READ_COMMITTED`, timeout 5초를 사용한다. 교착·lock timeout·낙관 충돌만 transaction 밖에서 최초 실행 포함 최대 3회 재시도하고, QR·시간·상태·권한 같은 업무 거절은 재시도하지 않는다.
- 예약금 취소 V2 worker의 claim/result transaction은 Reservation/process/MenuHold를 다시 잠그지 않는다. `APPLY`와 `QUERY` Payment 호출은 transaction 밖이며 QUERY 실패를 APPLY로 되돌리지 않는다.

## Migration·호환성 요구

- 예약은 `BIGINT` PK와 외부 문자열 ID를 사용한다.
- 수용량 버킷·배정 이력·정책 버전과 거래 스냅샷을 보존한다.
- 신규 예약은 server-side selector가 반환한 known `cancellationPolicyVersion`을 시간·수용량 정책 버전과 분리해 명시적으로 저장한다. 기존 행의 null·unknown version을 근거 없이 V1로 backfill하지 않으며, 해당 행은 V1으로 fallback하지 않는다.
- 신규 예약의 `startAt`, `serviceEndAt`, `occupancyEndAt`은 실제 날짜를 포함한 `Instant`로 저장하고 계산 당시 IANA 시간대·offset·duration을 함께 보존한다.
- 시간 정책 버전에는 게시 사유를 보존하고, due worker 조회 인덱스와 append-only lifecycle 감사 원장을 둔다.
- V15의 `serviceDate + startTime + endTime` 행은 offset을 추측해 소급 변환하지 않는다. 새 migration은 기존 값을 보존하고 Instant 스냅샷이 없는 과거 행을 신규 가용성 근거로 사용하지 않으며 고객 조회에는 `LEGACY_UNRESOLVED`를 명시한다.
- 매장 폐점·메뉴 종료·계정 정지가 과거 예약 행을 연쇄 삭제하지 않는다.
- 2차·고도화 상태를 추가할 때 기존 1차 enum 의미와 공개 코드를 재사용하지 않는다.
- V49 `V49__add_reservation_check_in_no_show.sql`은 `reservations`에 `NO_SHOW`와 nullable `no_show_at`을 추가하고, `cancelled_at`·`fulfilled_at`·`no_show_at`의 상태 일치 CHECK를 갱신한다. 과거 행을 `NO_SHOW`로 추정 backfill하지 않는다.
- V49는 reservation별 current QR grant 한 행, unique SHA-256 digest, 양수 version, Auth snapshot, 정확한 30초 issued/expires와 consumed 시각을 보존하는 `reservation_check_in_qr_grants`를 만든다.
- V49는 append-only `reservation_check_in_audits`와 reservation별 성공 한 건의 `reservation_no_show_audits`를 만들고 actor·이벤트·reason·전이·정책·command 유일성/CHECK를 DB에서 방어한다. raw QR·digest·opaque epoch는 audit에 복제하지 않는다.
- migration 순서는 #385 MenuHold `FORFEITED` V48 뒤 #240 V49이며, Draft #382는 V50 이상으로 조정한다.
- Issue #239 Payment 처분 원장은 V56·V57의 번호 점유 뒤 V58, Reservation disposition obligation은 V59다. V58/V59는 신규 원장·lease·fencing·Payment snapshot만 추가하고 기존 V1/null/unknown 예약을 갱신하지 않는다. PR #389/#400은 기능 의존성이 아니라 V56/V57 번호 점유만 선행한다.

## 인수 조건

- 메뉴가 없는 예약은 `MenuHold` 없이 확정된다.
- 메뉴가 있는 예약은 수용량과 모든 메뉴 수량이 함께 성공하거나 전체 실패한다.
- 모든 점유 구간의 인원 수와 팀 수가 한도를 초과하지 않는다.
- 같은 멱등 키 재전송과 같은 사용자 중복 예약이 별도 예약·이중 차감을 만들지 않는다.
- 일반 사용자 개인 자원의 부재와 다른 사용자 소유가 같은 404다.
- 취소가 예약·수용량·메뉴 홀드·수량을 한 번만 종결·복구한다.
- 기존 직접 방문 완료가 QR·시간 판정과 독립적으로 유지되고 수량을 복구하지 않는다.
- QR 발급은 raw token을 성공 응답 한 번에만 반환하고 서버에는 digest만 저장하며 30초·latest version only·무중첩 rotation을 지킨다.
- QR scan은 `[startAt, startAt + 5분)` 경계와 Auth epoch를 검증하고 성공 시 Reservation/MenuHold/기존 fulfillment audit/QR audit/grant 소비/멱등 결과를 한 번만 함께 commit한다.
- 정확히 `startAt + 5분`부터 필수 후보 reason의 no-show가 Reservation `NO_SHOW`, MenuHold `FORFEITED`, audit와 멱등 결과를 함께 commit한다.
- QR/no-show replay는 현재 매장 권한만 다시 확인하고 저장된 성공을 반환하며 raw QR·expiry·epoch·시간·과거 상태를 다시 검증하지 않는다.
- QR scan, no-show, direct fulfillment, cancellation 경합은 하나의 종결 상태만 남긴다. 방문 경로는 수용량·allocation·inventory·return ledger·transfer를 조회·복구하지 않으며 V2에서만 승인된 disposition obligation을 생성한다.
- 범용 status PATCH, 결제·환불·CHANGE_PENDING API가 없다.
- 같은 시작 시각이라도 서로 다른 매장 시간 정책은 서로 다른 `occupancyEndAt`을 만들며 입력 순서와 중복을 보존한다.
- 시간 계산에 성공한 후보만 `[startAt, serviceEndAt)`으로 Store batch 검증하며 turnover 구간을 보내지 않는다. Store의 `NOT_ACCEPTING`은 `UNAVAILABLE`이고 계약과 다른 batch 응답은 전체 실패 폐쇄된다.
- 고객 응답에 `timeStatus`, `serviceEndAt`, `timeZoneId`가 있고 `occupancyEndAt` 또는 모호한 `endTime`이 없다. 과거 행은 `LEGACY_UNRESOLVED`와 null 시각 필드로 안전하게 구분된다.
- 자정 넘김과 DST 누락·중복 시각이 날짜·offset 손실 없이 처리되거나 명시적으로 실패 폐쇄된다.
- 1·2차 MVP의 V1에서 권한·소유 및 `CONFIRMED` 조건을 통과한 `CONSUMER`·`STORE_OPERATOR`의 취소 결과는 `requestedAt`이 `startAt` 전·정각·후라는 시간만으로 달라지지 않는다.
- V1 판정은 저장된 `cancellationPolicyVersion`과 서버 중앙에서 한 번 얻은 `requestedAt`을 사용하며 client timestamp·client version·current-version fallback이 없다. 금전·환불·시간 구간·cutoff·`PAY-*` 참조는 공개 응답과 판정에 없다.
- 신규 예약금 Hold만 V2를 저장·승계하고 10분·48시간·24시간 포함 경계가 10000·5000·0 bps 목표 누적 환불률로 결정된다.
- V2 취소·자원 복구·감사·PENDING obligation이 한 transaction이며 Payment 외부 호출은 그 transaction 밖에서만 실행된다.
- V2 직접 방문 완료·QR 체크인·확정 no-show도 방문 상태·감사와 PENDING obligation을 한 transaction에 저장하고, 승인된 reason 매핑 밖의 `UNCLEAR`와 V1/null/unknown은 obligation을 만들지 않는다.
- `RECONCILIATION_REQUIRED`의 외부 실패·`FAILED/RETRYABLE` 결과 뒤에도 다음 operation은 QUERY이고 새 처분·환불을 실행하지 않는다.
- POST replay는 최초 projection, GET은 latest projection, V1/null/unknown은 null을 반환하며 Reservation은 Payment Entity·Repository를 참조하지 않는다.

## 추천안 결정 이력

| 날짜 | 결정 | 선택 이유 |
| --- | --- | --- |
| 2026-08-12 | 10분 임시 선점은 기존 예약과 분리된 `ReservationHold` aggregate와 V31 영속 계약으로 단계 도입 | 1차 MVP 즉시 확정 조회·취소 의미를 보존하고 #265~#267의 수용량·MenuHold·worker 검토를 작은 PR로 분리 |
| 2026-08-04 | Store 전체 서비스 구간 검증은 Issue #104 / PR #106 선행 계약을 소비 | Store 일정 원본·충돌 판정을 Reservation에 복제하지 않고 `[startAt, serviceEndAt)`과 turnover 책임 경계를 유지 |
| 2026-08-03 | 서비스 종료와 실제 점유 종료를 `serviceEndAt`·`occupancyEndAt`으로 분리 | 고객 표시 의미와 수용량 점유 의미를 섞지 않고 매장별 duration 적용 |
| 2026-08-03 | 실제 시각은 Instant와 IANA 시간대·offset 스냅샷으로 보존 | 자정 넘김과 DST 중복·누락 시각을 LocalTime 비교로 손실하지 않음 |
| 2026-08-03 | Store `windowEndAt`은 시작 접수 상한으로만 소비 | PR #84의 window를 실제 서비스·점유 종료로 오해하는 결합 방지 |
| 2026-07-28 | 생성 요청에서 endTime을 받지 않음 | 서버의 현재 슬롯·서비스 소요 정책과 다른 임의 구간 요청 차단 |
| 2026-08-06 | 1차 MVP의 신뢰 연락처는 Auth의 무작위 참조와 연락 가능 상태만 예약 스냅샷으로 사용 | 실제 인증 완료로 오해하지 않으면서 다른 소유자·연락처 주입과 전화번호 원문 복제를 차단 |
| 2026-07-28 | 검증된 계정 연락처를 예약 스냅샷으로 사용 | 2026-08-06 임시 신뢰 정책으로 인증 의미를 구체화하되 요청 본문 연락처 주입 금지와 스냅샷 원칙은 유지 |
| 2026-07-28 | 선택 메뉴 홀드를 예약 생성에 포함 | 프론트엔드의 두 쓰기 호출로 원자성을 대신하는 구조 방지 |
| 2026-07-28 | 취소·방문 완료를 명령 리소스로 분리 | 범용 status PATCH와 허용되지 않은 상태 전이 방지 |
| 2026-07-28 | 날짜별 수용량 버킷 전체 게시 | 시간대 일부 병합 규칙 차이와 기존 예약 소급 수정 방지 |
| 2026-07-28 | 1차 중간 선점은 생성 트랜잭션 내부로 제한 | 결제 없는 단계에 사용자용 10분 선점·만료 기술을 억지로 노출하지 않음 |
| 2026-08-06 | 취소 정책 V1은 저장 버전과 서버 중앙 `requestedAt`으로 판정 | Issue #168; 권한·소유 및 `CONFIRMED` 조건을 통과한 `CONSUMER`·`STORE_OPERATOR`에 `startAt` 시간 cutoff를 두지 않고, 금전·환불·시간 구간·`PAY-*` 연결은 고도화로 유예 |
| 2026-08-16 | 30초 server-stored opaque QR와 공통 5분 경계의 QR 방문 완료·운영자 필수 사유 노쇼를 Reservation 종결 명령으로 활성화 | Issue #240; Auth account-wide epoch와 MenuHold `FORFEITED` 선행 계약을 소비하고 money는 #241로 분리 |
