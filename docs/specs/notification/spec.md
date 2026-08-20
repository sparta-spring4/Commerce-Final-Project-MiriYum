# 기능 명세: 알림 계약

> 적용 단계: `고도화`
> 소유 Issue: 기본 계약 `#247`, Waiting IN_APP 확장 `#250`, 예약 방문 완료·노쇼 확장 `#426`, 소비자 읽음·전역 배지 `#500`
> 구현 선행: 각 확장 Runtime은 해당 contract-first PR과 원 도메인 선행 Runtime이 `dev`에 병합된 뒤 활성화한다. 예약 방문 완료·노쇼는 `#240`, Waiting은 `#271`·`#272`를 소비한다.

## 관련 정책 ID

- 알림: `NOTI-001`~`NOTI-010`, 특히 `NOTI-004`~`NOTI-009`
- 예약: `RES-013`, `RES-014`
- 체크인·노쇼: `CHECK-006`, `CHECK-007`
- 메뉴 홀드: `HOLD-009`, `HOLD-011`
- 웨이팅: `WAIT-010`, `WAIT-011`, `WAIT-016`, `WAIT-017`
- 데이터·API: `docs/07-data-and-api-contracts.md`

## 범위

### 포함

- Reservation·MenuHold·Pickup의 확정 사건, 예약의 방문 완료·노쇼 종결 사건과 Waiting의 확정 상태·입장 임박 사건에서 생성하는 IN_APP 알림 목적 카탈로그
- 원 사건을 Notification에 기록하는 내부 공개 Service·DTO·오류 의미
- 렌더링과 행동 유효성 재검증을 위한 원 도메인 공개 조회 경계
- Notification 소유의 소비자 본인 알림 이력 HTTP API와 cursor 계약
- 소비자 본인 알림의 읽음 시각, 미확인 개수와 개별·전체 읽음 HTTP 계약
- 소비자 알림 이력을 다시 조회하게 하는 `notifications.changed` SSE 공개 계약과 account-bound 재연결 cursor
- 최신 상태에 의한 미발송 작업 취소·만료·대체 규칙

### 제외

- 일반 메뉴 품절, 일반 메뉴 추천, 주변 대체 매장 추천, 가게 찜, 광고·판촉 알림
- Payment·환불·취소 자리 승계 목적과 원 사건
- Notification·Waiting SSE 운영 활성화·배포 수치 확정과 AUTO 접수 오픈 worker
- SMS·알림톡·푸시·이메일 provider와 실제 연락처 조회
- frontend 화면·실시간 동작 구현, Notification runtime·migration·worker 구현
- `NOTI-009`의 정확한 보관기간과 법률 문구

## 사용자 관점의 기능 동작

- 로그인한 일반 사용자는 자신에게 생성되어 현재 보관 중인 논리 알림만 조회한다.
- 알림은 원 거래 상태를 설명할 뿐 예약·취소·메뉴 대체의 성립 조건이 아니다.
- 직접 방문 완료와 QR 체크인은 같은 방문 완료 알림으로 표시하고, 매장 운영자가 직접 확정한 노쇼는 별도 노쇼 알림으로 표시한다. 두 알림은 환불·몰취·귀책·결제 결과를 뜻하지 않는다.
- 사용자는 알림에서 허용된 예약·픽업·대체 메뉴 검토 화면으로 이동할 수 있다. Waiting 목적은 이번 단계에서 행동 링크를 제공하지 않고 알림 이력의 자원 참조와 안전한 제목만 공개한다. 행동이 만료·취소·대체됐으면 화면 이동 전 API가 반환한 중앙 상태를 다시 확인한다.
- 로그인한 일반 사용자는 웹의 consumer 화면 헤더에서 본인 미확인 알림 개수를 확인하고 기존 마이페이지 알림 이력으로 이동한다. 개수는 알림 목록 진입이나 스크롤만으로 감소하지 않으며 개별 알림 선택 또는 명시적인 전체 읽음 명령으로만 변경한다.
- 예약·픽업 상세 행동을 선택하면 읽음 기록을 시도한 뒤 보호된 상세 화면으로 이동한다. 읽음 기록 실패는 원 거래 상태 확인과 행동 진입을 막지 않는다. 행동이 없는 Waiting 알림은 항목 선택으로 해당 알림만 읽음 처리한다.
- 일반 품절은 목록·상세 화면의 현재 재고 상태로 확인한다. 기존 확정 거래가 실제 영향을 받은 경우에만 영향 알림을 받는다.

## 1차 목적 계약

목적 코드, 필수성, 원 사건과 기본 행동의 정본은 `docs/service-policies/16-notification.md`의 `IN_APP Reservation·MenuHold·Pickup·Waiting 알림 목적 카탈로그`다. 이 명세와 OpenAPI는 해당 코드를 그대로 사용하며 별칭이나 frontend 전용 목적을 만들지 않는다.

- 1차 허용 채널은 `IN_APP` 하나다. 알림 이력에 조회 가능하게 내구성 기록한 시점을 `IN_APP` 전달 성공으로 기록한다. 전달 전 취소·최종 실패·대기 작업은 내부 작업·감사 상태로만 유지하고 공개 이력에 포함하지 않는다.
- 목적 카탈로그에 대응하는 원 상태·사건이 아직 활성화되지 않았으면 그 목적은 작업을 만들지 않는다. 다른 활성 목적의 구현과 완료를 막지 않는다.
- 방문 안내는 원 예약의 `startAt`, `timeZoneId`, 활성 `timingPolicyVersion`으로 계산한 `scheduledAt`을 사용한다. 정확한 선행 시간은 정책 버전이 소유하며 이벤트 생산자나 worker가 임의 숫자를 사용하지 않는다.
- `RESERVATION_VISIT_COMPLETED`는 직접 방문 완료와 QR 체크인이 확정한 `FULFILLED` 하나에 수렴하고, `RESERVATION_NO_SHOW`는 매장 운영자가 직접 확정한 `NO_SHOW`에만 대응한다. 시간 경과·노쇼 후보·QR 실패는 두 목적을 만들지 않는다.
- 두 예약 종결 목적은 즉시 IN_APP 목적이며 보호된 예약 상세 route가 활성화되기 전에는 `action=null`이다. frontend 전용 URL이나 금전 결과 문구를 만들지 않는다.

## 내부 원 사건 계약

### 기록 Service

Notification은 source domain이 같은 MySQL 트랜잭션 안에서 호출할 다음 공개 경계를 소유한다.

```text
NotificationTaskRecorder.record(NotificationSourceEventV1 event)
  -> NotificationTaskReceipt(notificationId, duplicate)
```

- 같은 멱등 식별의 재호출은 아래 canonical payload fingerprint까지 같을 때만 기존 `notificationId`와 `duplicate=true`를 반환한다. fingerprint가 다르면 기존에 커밋된 최초 작업을 유지하고 `new ServiceException(NotificationErrorCode.SOURCE_EVENT_CONFLICT)`으로 거절하며 일부 필드만 덮어쓰거나 새 작업으로 만들지 않는다. 이 ErrorCode tuple은 `HttpStatus.CONFLICT`, 외부 코드 `NOTIFICATION_002`, 메시지 `동일한 알림 원 사건 식별자를 다른 내용으로 사용할 수 없습니다.`로 고정한다.
- 새 작업의 내구성 기록 실패는 원 업무 트랜잭션도 성공시키지 않는다. 작업이 기록된 뒤의 전달·렌더링·재시도 실패는 원 상태를 되돌리지 않는다.
- producer는 Notification Entity·Repository를 직접 접근하지 않고 이 Service만 사용한다.
- `SOURCE_EVENT_CONFLICT`는 `NotificationTaskRecorder`를 호출한 원 업무 트랜잭션 밖으로 전파해 해당 명령의 변경도 rollback한다. 동기 HTTP producer 명령에서 발생하면 기존 `GlobalExceptionHandler`가 공통 오류 envelope의 HTTP 409·`NOTIFICATION_002`로 응답하며, 소비자 알림 이력 GET에서 발생하거나 노출되는 오류가 아니다.
- 이 복합 멱등 식별은 HTTP 요청의 `Idempotency-Key`가 아니므로 `COMMON_007`로 변환하지 않는다. `#248`이 `NotificationErrorCode.SOURCE_EVENT_CONFLICT`와 recorder 구현을 만들고, producer 연동 Issue는 이 `ServiceException`을 잡아 성공으로 바꾸거나 원 도메인 오류로 재매핑하지 않으며 영향받는 producer OpenAPI의 409 응답에 `NOTIFICATION_002`를 추가한다.

### `NotificationSourceEventV1`

| 필드 | 형식 | 규칙 |
|---|---|---|
| `sourceEventId` | non-blank string, 최대 100 | 원 도메인이 `sourceDomain` 안에서 같은 확정 사건 재처리에도 재사용하는 식별자 |
| `sourceDomain` | `RESERVATION`, `MENU_HOLD`, `PICKUP`, `WAITING` | 원 사건 소유 도메인 |
| `purpose` | 목적 카탈로그 enum | 원 상태와 일치하지 않으면 기록 거부 |
| `recipientAccountId` | 양의 public ID string | 대표 일반 사용자 계정 하나 |
| `recipientRelationVersion` | 양의 64-bit integer | 원 자원과 수신자 관계 버전 |
| `resourceType` | `RESERVATION`, `MENU_HOLD`, `PICKUP_RESERVATION`, `MENU_SUBSTITUTION_PROPOSAL`, `WAITING_TEAM` | 행동과 최신 상태 재검증의 기준 |
| `resourceId` | 양의 public ID string | 원 자원 식별자 |
| `resourceVersion` | 양의 64-bit integer | 원 상태·제안 버전 |
| `sourceState` | non-blank string, 최대 64 | 목적 카탈로그의 허용 원 상태 |
| `occurredAt` | offset 포함 date-time | 원 사건 중앙 발생 시각이자 이력 정렬 1차 키 |
| `scheduledAt` | offset 포함 date-time | 즉시는 `occurredAt`, 예약 목적은 정책 계산 결과 |
| `expiresAt` | nullable offset date-time | 행동 또는 예약 작업 만료 시각. 원 권리의 기한을 연장하지 않음 |
| `timingPolicyVersion` | nullable 양의 64-bit integer | `RESERVATION_VISIT_REMINDER`에 필수, 즉시 목적에는 null |
| `correlationId` | non-blank string, 최대 100 | 원 명령·감사 상관관계 |

사건에는 전화번호·이메일·수신 주소·완성된 제목/본문·동행자·다른 구매자·결제수단·알레르기 원문을 넣지 않는다. 예약 방문 완료 사건에는 raw QR·digest·opaque Auth epoch를, 노쇼 사건에는 후보 사유 원문을 추가로 넣지 않는다. `scheduledAt`과 값이 있는 `expiresAt`은 `occurredAt`보다 과거일 수 없다. 예약 발송 목적 `RESERVATION_VISIT_REMINDER`는 `scheduledAt`·`expiresAt`·`timingPolicyVersion`이 모두 필수이고 `scheduledAt < expiresAt`이어야 한다. 즉시 예약 종결 목적은 `scheduledAt=occurredAt`, `expiresAt=null`, `timingPolicyVersion=null`이다.

### 논리 멱등 식별

논리 알림은 다음 값의 정규화 결합으로 식별한다.

```text
sourceDomain + sourceEventId + recipientAccountId + purpose + resourceType + resourceId + resourceVersion
```

`correlationId`, 주소, title, worker instance와 채널 시도 ID는 논리 멱등 식별에 참여하지 않는다. 채널 시도는 같은 논리 알림 아래 별도 식별자를 사용한다.

멱등 키가 같을 때 비교할 canonical payload fingerprint는 다음 필드를 RFC 8785 JSON Canonicalization Scheme(JCS)으로 직렬화한 UTF-8 bytes의 SHA-256 lowercase hex다. RFC 8785가 정한 object property 정렬, ECMAScript 문자열 직렬화와 escape 형식을 그대로 사용하며 별도의 pretty-print, `/` escape, 임의 `\uXXXX` 변환이나 escape hex 대소문자 선택을 허용하지 않는다.

- JSON object는 `contractVersion`, `recipientRelationVersion`, `sourceState`, `occurredAt`, `scheduledAt`, `expiresAt`, `timingPolicyVersion` key를 모두 포함하며 실제 byte 순서는 RFC 8785 property sorting을 따른다.
- `contractVersion`은 문자열 `notification-source-event-v1`이다. `sourceState`는 입력 검증을 통과한 원문을 JSON escaping만 적용해 사용하며 trim·대소문자 변경·Unicode 재정규화를 하지 않는다.
- date-time은 UTC로 변환한 뒤 초 단위 `yyyy-MM-dd'T'HH:mm:ss'Z'`로 직렬화한다. 입력에 0이 아닌 초 미만 정밀도가 있으면 기록을 거절해 반올림·절삭 차이를 허용하지 않는다.
- 필수 `scheduledAt`은 항상 정규화한 date-time string으로 넣는다. nullable `expiresAt`, `timingPolicyVersion`은 key를 생략하지 않고 값이 없을 때 JSON `null`로 직렬화한다. 64-bit 값인 `recipientRelationVersion`과 값이 있는 `timingPolicyVersion`은 IEEE-754 정밀도 손실을 피하기 위해 JSON number가 아니라 leading zero 없는 부호 없는 10진 JSON string으로 직렬화한다.
- `correlationId`는 호출·감사 추적 메타데이터이므로 fingerprint 원문에 포함하지 않는다. 새 작업은 최초 호출의 `correlationId`를 원인 명령 추적값으로 보존한다. 같은 사건·내용을 다른 `correlationId`로 재호출하면 기존 작업의 값을 덮어쓰지 않고 기존 `notificationId`와 `duplicate=true`를 반환하며, 새 호출의 `correlationId`는 해당 호출 감사·추적에만 사용한다.
- fingerprint 원문은 위 사건 내용 필드 외 값을 포함하지 않으며 공급자 payload, 완성된 메시지 본문이나 수신 주소를 추가하지 않는다. 작업에는 fingerprint와 `contractVersion`을 함께 저장하고 다른 계약 버전의 fingerprint를 같은 값으로 간주하지 않는다.

## 원 도메인 공개 조회 계약

Notification은 작업 실행과 이력 행동 계산에 필요한 다음 읽기 경계를 소유하고 각 원 도메인은 해당 구현체를 제공한다. 포트 인터페이스와 공통 DTO는 Notification package에 두며, 원 도메인의 구현체는 Notification의 공개 포트만 의존하고 Entity·Repository를 노출하지 않는다. 따라서 기록 호출과 조회 구현의 package 의존은 모두 Reservation·MenuHold·Pickup·Waiting → Notification 한 방향으로 유지한다.

```text
ReservationNotificationSource.readContext(purpose, resourceId, expectedVersion, recipientAccountId)
MenuHoldNotificationSource.readContext(resourceType, resourceId, expectedVersion, recipientAccountId)
PickupNotificationSource.readContext(resourceId, expectedVersion, recipientAccountId)
WaitingNotificationSource.readContext(resourceId, expectedVersion, recipientAccountId)
  -> NotificationSourceContextV1
```

조회 포트와 원 사건 자원 소유는 다음과 같이 고정한다.

| 조회 포트 | 처리하는 `resourceType` |
|---|---|
| `ReservationNotificationSource` | `RESERVATION` |
| `MenuHoldNotificationSource` | `MENU_HOLD`, `MENU_SUBSTITUTION_PROPOSAL` |
| `PickupNotificationSource` | `PICKUP_RESERVATION` |
| `WaitingNotificationSource` | `WAITING_TEAM` |

Pickup은 자신의 픽업 예약 확정·취소 사건을 같은 업무 트랜잭션에서 `NotificationTaskRecorder`에 직접 기록한다. `MenuHoldNotificationSource`는 `PICKUP_RESERVATION`을 처리하지 않으며 MenuHold → Pickup 역방향 의존을 만들지 않는다. 메뉴 이행 위험과 대체 제안·결과의 원 사건은 MenuHold가 소유하고, 연결된 `PICKUP_RESERVATION`은 `actionResourceType`·`actionResourceId`로만 반환할 수 있다.

Reservation·Pickup producer 연동에서 `resourceVersion`은 상태 enum ordinal이 아니라 확정 알림 원 사건 revision이다. 확정 사건은 `1`, 취소·방문 완료·노쇼 terminal 사건은 `2`다. 직접 방문 완료와 QR 체크인은 경로가 달라도 같은 예약의 `FULFILLED` revision `2`에 수렴하고, `NO_SHOW`도 terminal revision `2`를 사용한다. Pickup `PICKED_UP`은 새 원 사건을 만들지 않고 최신 revision `2`를 유지한다. 조회 소비자는 version 값만으로 상태를 추론하지 않고 요청 `purpose`, `sourceState`와 `result=SUPERSEDED`를 함께 사용한다. 두 자원의 소유 일반 사용자 계정 관계는 현재 변경 불가능하므로 `recipientRelationVersion=1`로 고정하며, 이 값은 연락처나 채널 주소의 version이 아니다.

예약 종결 producer의 `sourceEventId`는 성공한 terminal 전이를 재처리해도 같은 값을 재사용한다. 원 command 경로, worker instance와 QR token은 식별자에 넣지 않으며, 동일 예약·수신자·목적·terminal revision의 replay는 하나의 논리 알림으로 수렴한다. `NotificationSourceRegistry`는 작업의 `purpose`를 `ReservationNotificationSource`에 그대로 전달한다. 이 포트는 `RESERVATION_VISIT_COMPLETED`와 현재 `FULFILLED`, 또는 `RESERVATION_NO_SHOW`와 현재 `NO_SHOW`의 정확한 조합만 `FOUND`로 반환하고 두 terminal 목적의 action tuple은 모두 null로 반환한다. 기존 목적은 목적별 현재 상태와 기존 action 의미를 유지한다.

Waiting의 `resourceVersion`은 양수 계약을 유지하기 위해 원 사건의 `eventSequence`를 사용한다. 상태 사건의 `eventSequence`는 `WaitingTeam.version + 1`이며 생성 상태의 team version `0`은 event sequence `1`이다. `WAITING_ENTRY_IMMINENT`는 최초 적격 판정 시의 event sequence를 별도 원장에 고정한다. 이 값은 원 사건의 멱등 식별이며 이후 모든 team version과의 일치를 요구하는 대체 판정값이 아니다. Waiting 조회 구현은 요청된 원 사건과 목적별 현재 상태를 함께 검증하고, 사건이 여전히 유효하면 요청된 event sequence를 context의 `resourceVersion`으로 반환한다. 수신자 관계는 해당 팀의 불변 `consumerAccountId`에 결속한다.

`NotificationSourceContextV1`은 다음 안전 필드만 반환한다.

- 현재 `resourceVersion`, `recipientRelationVersion`, `sourceState`
- `storeDisplayName`
- nullable `resourceDisplayName` — 메뉴 표시명이 필요한 목적만 사용
- nullable `scheduledAt`, `expiresAt`
- nullable `actionType`, `actionResourceType`, `actionResourceId`, `actionAvailability`

행동이 없으면 위 네 필드를 모두 null로 반환한다. 행동이 있으면 네 필드를 모두 non-null로 반환하고 공개 행동 계약의 `action.type`·`action.resource.type` 허용 조합을 따르며 `actionResourceId`는 양의 public ID여야 한다. 일부 필드만 null인 행동 tuple은 반환하지 않는다.

읽기 결과는 다음 네 가지로 구분한다.

| 결과 | Notification 처리 |
|---|---|
| `FOUND` | 현재 버전과 수신 관계를 검증해 렌더링 또는 행동 공개 |
| `SUPERSEDED` | 오래된 미발송 작업 취소, 이력 행동은 `SUPERSEDED` |
| `NOT_ELIGIBLE` | 발송 취소·보안 감사, 다른 수신자를 추측하지 않음 |
| `TEMPORARILY_UNAVAILABLE` | 원 상태를 바꾸지 않고 Notification 작업만 제한 재시도 |

원 자원이 보이지 않는 경우와 수신자 불일치는 외부에 자원 존재 여부를 공개하지 않는다. Reservation·MenuHold·Pickup 구현 PR은 정상·최신 버전 대체·수신자 불일치·일시 장애 계약 테스트를 각각 제공한다.

Waiting 구현도 같은 네 결과를 사용한다. `WAITING_ENTRY_IMMINENT`는 현재 상태가 `WAITING`이면 최초 event sequence로 `FOUND`다. 현재 상태가 `RESERVATION_CONVERTING`이면 성공·실패가 확정될 때까지 작업을 취소하거나 최종 실패로 보내지 않는 Waiting 전용 `TEMPORARILY_UNAVAILABLE` 보류다. 이 context는 요청된 event sequence와 `sourceState=RESERVATION_CONVERTING`을 반환해 원장 조회 장애와 구분한다. 이 보류는 일반 일시 장애의 bounded retry 횟수를 소비하지 않고 상태 사건 기반 재판정과 유한한 주기 재조회로 다시 판정한다. 전환 실패로 같은 활성 membership의 `WAITING`에 복귀하면 최초 사건은 다시 `FOUND`다. 실제 호출 `CALLED`, 도착 `ARRIVED`, 예약 전환 완료 또는 다른 종결 상태가 확인되면 오래된 입장 임박 사건은 `SUPERSEDED`다. 상태 사건 목적은 현재 상태가 해당 목적과 일치할 때만 `FOUND`이고 더 최신 상태가 있으면 `SUPERSEDED`다. 팀의 수신자와 요청 수신자가 다르면 `NOT_ELIGIBLE`, 원장 조회의 일시 장애는 bounded retry를 사용하는 `TEMPORARILY_UNAVAILABLE`다. `WAITING_CALLED`의 context는 중앙 `calledAt`을 `scheduledAt`, 정확히 10분 뒤의 `arrivalDeadline`을 `expiresAt`으로 반환한다.

### Waiting 보류·재판정 fencing

`notification_tasks.version`은 Notification 작업 자체의 단조 증가 fencing 값이며 원 사건의
`resourceVersion`과 다른 값이다. Worker는 lease 획득으로 증가한 현재 작업 version을
`claimedTaskVersion`으로 보존한다. 전달·취소·최종 실패·일반 재시도와 Waiting 보류를 포함한
모든 Worker 갱신은 `status=PENDING`, 동일한 유효 `leaseToken`, 동일한
`claimedTaskVersion`을 조건으로 수행한다. 조건부 갱신이 실패한 Worker는 이전 조회 결과로
작업을 완료하거나 다시 보류하지 않고 현재 작업과 원 상태를 다시 읽는다.

Waiting 전용 보류는 작업을 `PENDING`으로 유지하고 lease를 반납하며, bounded retry
`attemptCount`를 소비하지 않는다. `nextAttemptAt`은 유효한 versioned Notification runtime
정책의 유한한 재조회 시점으로 설정한다. 정책이 없거나 유효하지 않으면 기존 fail-closed worker
경계를 유지하고 전달 성공을 추측하지 않는다. 재조회 시 여전히 `RESERVATION_CONVERTING`이면
같은 조건부 보류를 반복하고, `WAITING`이면 최초 사건을 `FOUND`, 실제 호출·도착·예약 전환 완료
또는 다른 종결 상태이면 `SUPERSEDED`로 수렴한다. 주기 재조회는 상태 사건 처리 누락에 대한
안전망이며 정상 경로의 사건 기반 재판정을 대체하지 않는다.

Notification이 소유하는 재판정 경계는 `WAITING_TEAM` ID와 Waiting 상태 사건 ID·
`eventSequence`를 입력으로 받아 해당 팀의 모든 `PENDING` `WAITING_ENTRY_IMMINENT` 작업을
대상으로 한다. 작업이 현재 lease 중인지 이미 보류됐는지 구분하지 않고 작업 version을
증가시키며 기존 lease를 무효화하고 `nextAttemptAt`을 DB 현재 시각으로 당긴다. 대상이 없거나
이미 `DELIVERED`, `FAILED`, `CANCELLED`이면 상태를 되돌리지 않는 멱등 no-op이다. 같은 MySQL
트랜잭션에서 이 내구성 갱신과 Waiting 상태 사건의 `PUBLISHED` 전이를 함께 확정한다.

이 순서로 Worker 보류가 먼저 커밋되면 뒤이은 재판정이 작업을 즉시 깨운다. 상태 사건 재판정이
먼저 커밋되면 version 증가와 lease 무효화 때문에 이전 `claimedTaskVersion`의 Worker 보류가
실패하고 Worker가 최신 상태를 다시 읽는다. 따라서 `WAITING` 복귀 사건이 Worker의 보류 기록보다
먼저 처리돼도 lost wakeup으로 영구 정지하지 않는다.

## 상태 대체·만료 계약

- 예약 취소·만료·방문 완료·노쇼는 같은 예약의 아직 미발송된 확정·변경·방문 안내를 취소한다.
- 예약 변경이 확정되면 이전 버전의 방문 안내를 취소하고 유효한 시점 정책이 있을 때 새 버전 안내를 생성한다.
- `RESERVATION_COORDINATION_REQUIRED`는 최종 변경·취소 또는 원 영향 사건 해소 뒤 행동을 `SUPERSEDED`로 반환한다. 열람·침묵·알림 실패는 조율 동의가 아니다.
- 메뉴 이행 위험 사건은 원래 이행 확정, 대체 제안, 거래 취소 중 최신 결과가 도착하면 이전 미발송 작업을 대체한다.
- 대체 제안은 수락·거절·만료·거래 취소 중 하나가 확정되면 이전 행동을 `SUPERSEDED` 또는 `EXPIRED`로 반환한다. 전달 지연과 열람은 `expiresAt`을 연장하지 않는다.
- 이미 전달된 알림의 내용이 중요하게 바뀌면 기존 이력을 수정해 다른 의미로 만들지 않고 새 상태 버전의 논리 알림을 생성한다.
- Waiting의 `CALLED`, `ARRIVED`, `RESERVATION_CONVERTED`, `CANCELLED`, `NO_SHOW`, `CHECKED_IN`, `CLOSED_BY_STORE`는 더 오래된 `WAITING_ENTRY_IMMINENT` 미발송 작업을 대체한다. `ARRIVED`와 종결 상태는 오래된 `WAITING_CALLED` 미발송 작업도 대체한다. `ARRIVED`와 `RESERVATION_CONVERTED`는 이번 단계에서 별도 알림 목적을 만들지 않는다. `RESERVATION_CONVERTING`은 성공 시 대체 여부와 실패 시 복귀 여부가 아직 확정되지 않은 일시 상태이므로 그 자체로 입장 임박 작업을 영구 대체하지 않는다.

## 소비자 알림 이력 HTTP 계약

### Endpoint

```http
GET /api/v1/consumers/me/notifications?cursor={opaqueCursor}&size={1..50}
Authorization: Bearer {consumerAccessToken}
```

- `cursor`는 선택이며 없으면 최신 발생 시각부터 조회한다.
- `size` 기본값은 20, 최대값은 50이다.
- 정렬은 `occurredAt DESC, notificationId DESC`로 고정한다.
- cursor는 정렬 tuple과 계약 버전을 포함하는 무결성 보호 opaque 값이다. client는 해석·수정하지 않는다.
- cursor가 가리킨 행이 보관 정책으로 삭제돼도 tuple 경계로 다음 페이지를 조회한다. 형식·버전·무결성이 잘못된 cursor만 `NOTIFICATION_001`로 거절한다.
- 빈 이력은 `200`과 빈 `items`, `hasNext=false`, `nextCursor=null`이다.

`notificationId`는 논리 알림과 `IN_APP` 이력 레코드가 공유하는 공개 식별자다. `NotificationTaskReceipt`와 이력 응답은 같은 값을 사용하며 별도의 `notificationHistoryId`를 만들지 않는다. 외부 채널 시도는 공개하지 않는 독립 시도 식별자를 사용하므로 이 정렬 키를 바꾸지 않는다.

### 공개 항목

- `notificationId`, `purpose`, `title`
- `resource`의 `type`, `id`
- `occurredAt`, `createdAt`, 필수 `deliveredAt`
- nullable `readAt`; `null`이면 현재 계정에서 미확인이고 값이 있으면 서버가 확정한 최초 읽음 시각
- nullable `action`이 있으면 `type`, `resource`의 `type`·`id`, `availability`, nullable `expiresAt`

`title`은 승인된 template field allowlist로 렌더링한 최대 100자의 안전한 제목이다. 원문 주소·전체 메시지 본문·provider payload·내부 재시도 횟수·감사 메모는 반환하지 않는다.

### 내부 작업 상태와 공개 가시성

Notification 내부 작업은 `PENDING`, `DELIVERED`, `FAILED`, `CANCELLED`를 유지한다. 소비자 공개 이력에는 `IN_APP` 전달이 성공해 `deliveredAt`이 확정된 `DELIVERED` 작업만 노출한다. `PENDING`, `FAILED`, `CANCELLED`는 내부 작업·시도·감사 기록에만 보존하며 공개 응답에 전달 상태 필드를 만들지 않는다.

### 소비자 읽음·미확인 개수 계약

```http
GET /api/v1/consumers/me/notifications/unread-count
PUT /api/v1/consumers/me/notifications/{notificationId}/read
PUT /api/v1/consumers/me/notifications/read-all
Authorization: Bearer {consumerAccessToken}
```

- 세 endpoint는 활성 consumer 본인에게 공개된 `IN_APP DELIVERED` 알림만 대상으로 한다. account ID를 path·query·body로 받지 않고 인증 principal에 결속한다.
- `GET .../unread-count`와 두 읽음 명령의 성공 응답 data는 `{ "unreadCount": 0 이상의 정수 }`다. client가 보낸 개수나 읽음 시각은 받지 않는다.
- 개별 읽음은 `notificationId`가 본인 공개 이력에 속하고 `readAt=null`일 때 DB 현재 시각을 최초 `readAt`으로 기록한다. 이미 읽은 같은 알림의 반복 요청은 최초 시각을 바꾸지 않고 현재 개수로 `200`을 반환하는 멱등 no-op이다.
- 다른 계정, 미전달, 실패, 취소, title이 없어 공개되지 않는 알림과 존재하지 않는 ID는 모두 `404 NOTIFICATION_003`으로 응답해 존재 여부를 구분하지 않는다.
- 전체 읽음은 명령 transaction이 직렬화된 시점까지 본인 공개 이력에 포함되고 `readAt=null`인 모든 알림에 같은 DB 현재 시각을 기록한다. 이미 전부 읽은 반복 요청은 `200`, `unreadCount=0`인 멱등 no-op이다.
- 미확인 개수의 원장은 별도 counter가 아니라 공개 전달 조건을 모두 만족하고 `readAt=null`인 본인 알림 집합이다. 이 조건을 지원하는 계정·공개 상태·읽음 인덱스로 집계하며 page size나 현재 client cache에서 개수를 추정하지 않는다.
- 읽음은 공개 표시 상태일 뿐 원 예약·픽업·웨이팅 상태, 전달 성공, 행동 가용성, 보관기간과 삭제를 변경하지 않는다. `readAt`은 `deliveredAt`보다 빠를 수 없다.

### 읽음·전달 동시성과 변경 version

- `notification_consumer_change_states`는 consumer account별 단조 `change_version`만 저장한다. 미확인 개수를 중복 저장하지 않는다.
- 새 공개 `IN_APP DELIVERED`, 최초 개별 읽음과 실제 변경이 있는 전체 읽음은 같은 account 변경 상태 행을 먼저 잠근 뒤 알림 상태와 `change_version`을 같은 MySQL transaction에서 확정한다.
- 개별 읽음은 `read_at IS NULL` 조건부 갱신을 사용해 병렬 요청 중 최초 한 건만 상태를 바꾼다. 전체 읽음과 새 전달도 같은 account 잠금 순서를 사용하므로 직렬 순서상 전체 읽음 뒤에 공개된 새 전달만 미확인으로 남는다.
- 새 전달의 version은 `max(current changeVersion + 1, notificationId)`이고 읽음 변경은 `current changeVersion + 1`이다. state가 없는 계정은 현재 공개 최대 `notificationId` 또는 0을 기준으로 생성한다.
- migration은 기존 공개 전달 완료 알림의 `readAt`을 `deliveredAt`으로 backfill하고 account state의 `changeVersion`을 기존 공개 최대 `notificationId`로 초기화한다. 따라서 기능 배포 전 알림은 갑자기 미확인 배지에 포함되지 않는다.
- Backend rolling replacement 중 구 worker가 새 전달을 완료해 state 갱신을 모르는 구간을 회수하도록 Notification SSE adapter는 `max(account changeVersion, 현재 공개 최대 notificationId)`를 읽는다. 읽음 endpoint를 소비하는 Frontend는 Backend 계약·Runtime이 전체 배포된 뒤 활성화한다.
- 실제 공개 상태가 바뀌지 않은 반복 읽음은 `changeVersion`을 증가시키거나 SSE wake-up을 만들지 않는다. transaction commit 뒤 wake-up 실패는 읽음·전달을 되돌리지 않고 MySQL correction이 최신 version을 회수한다.

### 행동 계약

임의 URL 대신 다음 `action.type`만 공개한다.

- `RESERVATION_DETAIL`
- `PICKUP_RESERVATION_DETAIL`
- `MENU_SUBSTITUTION_REVIEW`

`action.type`과 `action.resource.type`의 허용 조합은 다음 세 가지뿐이다.

| `action.type` | `action.resource.type` |
|---|---|
| `RESERVATION_DETAIL` | `RESERVATION` |
| `PICKUP_RESERVATION_DETAIL` | `PICKUP_RESERVATION` |
| `MENU_SUBSTITUTION_REVIEW` | `MENU_SUBSTITUTION_PROPOSAL` |

목록에 없는 교차 조합은 생성·저장·공개하지 않는다.

`availability`는 `AVAILABLE`, `EXPIRED`, `SUPERSEDED`, `UNAVAILABLE` 중 하나다. frontend는 `AVAILABLE`만 실행하고 나머지는 비활성화한다. 실행 시 대상 API의 현재 권한과 상태를 다시 검증하며 알림 응답만으로 변경·수락을 확정하지 않는다.

## 소비자 알림 이력 SSE 계약

```http
GET /api/v1/consumers/me/notification-events
Authorization: Bearer {consumerAccessToken}
Last-Event-ID: {opaqueCursor} # 재연결일 때만
Accept: text/event-stream
```

- 브라우저는 Authorization header를 전달할 수 있는 fetch streaming을 사용한다. 성공 media type은 `text/event-stream`이다. PR #474가 세 production Runtime route와 공통 transport를 활성화했으며, 실제 환경 활성화와 proxy·부하·장애 증거는 #250 배포 검증이 소유한다.
- 업무 event 이름은 `notifications.changed` 하나다. event data는 알림 상태 본문이나 전달 성공의 근거가 아니며, client는 신호를 받으면 알림 이력과 미확인 개수 HTTP API를 다시 조회한다.
- `Last-Event-ID`가 없으면 최초 연결이다. 연결 직후와 유효한 재연결 뒤 현재 MySQL high-watermark에 결속된 changed signal을 한 번 보내며 이후 신호는 중복 병합할 수 있다.
- wire frame은 `event: notifications.changed`, opaque `id`, 고정 `data: {}` 세 줄 뒤 필수 빈 줄을 두어 `\n\n`으로 종료한다. 빈 data 객체에 계정·알림·목적·자원·상태 필드를 추가하지 않는다.
- `id`는 consumer audience·인증 계정·계약 version에 결속한 1~512자의 base64url 문자 집합 opaque cursor다. 형식·무결성이 잘못됐거나 다른 audience·계정 cursor면 `400 COMMON_001` JSON 오류 envelope로 거절한다.
- 최초 연결·유효한 재연결의 수렴 신호는 이력이 비어 있거나 high-watermark가 바뀌지 않았어도 한 번 보낸다. 그 뒤에는 공개 이력에 새 `IN_APP DELIVERED`가 보이거나 최초 개별 읽음·실제 전체 읽음으로 공개 읽음 상태가 바뀐 경우만 신호 대상이다. 반복 읽음 no-op, 내부 `PENDING`, `FAILED`, `CANCELLED`, channel attempt와 provider 결과는 제외한다. keepalive comment는 업무 event나 성공 근거가 아니며 cursor를 전진시키지 않는다.
- Valkey Pub/Sub은 인스턴스 간 wake-up hint이고 MySQL이 유일한 재연결·보정 원본이다. 신호 유실·중복·역순과 구독 재시작 뒤에도 유한한 MySQL correction과 HTTP 재조회로 수렴한다.

Waiting consumer·store-operator SSE endpoint와 `waiting.changed`의 영향 범위는 Waiting 기능 명세가 소유한다. 공통 transport·cursor·connection registry·Valkey·MySQL correction Runtime은 PR #474에 병합됐고, Nginx·배포 설정·부하 및 장애 검증은 #250의 별도 exact allowlist가 소유한다.

## 오류 계약

| 코드 | 공개 HTTP 또는 내부 결과 | 의미 |
|---|---:|---|
| `NOTIFICATION_001` | 400 | cursor 형식·버전·무결성이 유효하지 않음 |
| `NOTIFICATION_002` | 409 — 원 사건 producer 명령 | 같은 논리 멱등 식별에 저장된 canonical payload와 다른 payload가 기록됨. `ServiceException(NotificationErrorCode.SOURCE_EVENT_CONFLICT)`으로 전파하며 소비자 알림 이력 GET의 오류로 노출하지 않음 |
| `NOTIFICATION_003` | 404 | 요청한 알림이 본인의 공개 전달 완료 이력에 없거나 존재하지 않음. 타 계정·비공개 상태를 구분하지 않음 |
| `COMMON_001` | 400 | `size` 등 요청 값 검증 실패 또는 SSE `Last-Event-ID` 형식·무결성·계정 결속 실패 |
| `AUTH_001` | 401 | 사용할 수 있는 일반 사용자 인증이 없음 |
| `AUTH_011` | 403 | 현재 계정 상태가 조회를 허용하지 않음 |
| `COMMON_012` | 503 | 이력 중앙 저장소 등 필수 의존성을 일시적으로 사용할 수 없음 |

타 사용자의 계정 ID를 path나 query로 받지 않으며 타인 이력을 찾는 `404` 계약을 만들지 않는다.

## Migration·호환성 요구

- `#248`이 만든 Notification 원장·worker·조회 Runtime과 repository는 계속 Notification 도메인이 소유한다. 앞선 #250 IN_APP 계약 PR #384는 Waiting 목적·자원·조회·재판정 경계만 확정했고 production 구현은 Runtime PR #399가 기존 Notification worker와 repository를 확장해 제공한다.
- #250 IN_APP Runtime PR #399의 Notification 소유 변경은 작업 version fencing, Waiting 보류와 공개 재판정 Service를 구현한다. Waiting 소유 변경은 상태 사건 dispatcher에서 그 Service를 호출하고 `PUBLISHED`를 같은 원자 경계에 두는 것뿐이다. 기존 일반 일시 장애의 재시도·최종 실패 의미를 Waiting 보류에 재사용하거나 Waiting이 Notification Entity·Repository를 직접 접근하지 않는다.
- `#426` contract-first PR은 예약 방문 완료·노쇼 목적과 source 상태·대체 규칙만 확정하며 production Java, migration과 worker 설정을 변경하지 않는다. Runtime은 이 계약 PR이 `dev`에 병합된 뒤 최신 `dev`의 충돌과 migration 번호를 확인해 exact allowlist를 Issue에 추가하고, 기존 Notification 원장·worker·Reservation producer를 확장하는 별도 PR로 구현한다.
- 목적·source event·cursor는 버전 필드를 가져야 한다. 새 목적과 nullable 필드는 하위 호환 추가만 허용하고 기존 enum 의미를 재사용하지 않는다.
- `NOTI-009` 확정 전에도 보관 만료를 적용할 수 있는 구조를 갖추되 영구 보존이나 임의 삭제 기간을 기본값으로 넣지 않는다.
- 외부 채널 추가는 논리 알림과 `IN_APP` 이력이 공유하는 `notificationId`를 바꾸지 않고 같은 논리 알림 아래 내부 채널 시도만 추가한다.
- #250 SSE 계약 PR #442와 Runtime PR #474가 세 변경 신호 path·재연결 cursor·HTTP 수렴 및 production Java Runtime을 제공한다. 환경 기본 활성화, proxy·부하·장애 증거는 #250 배포 검증이 소유하고, frontend 생성 타입과 소비 구현은 #251·#410·#411이 각각 소유한다.
- `#500` contract-first PR은 `read_at`, account change state, 읽음·개수 API와 read-aware Notification watermark를 먼저 `dev`에 병합한다. Frontend 전역 배지는 이 Backend 배포가 완료되고 PR #495가 병합된 뒤 생성 타입만 소비한다.
- `V68`은 열린 PR #489의 `V67` 병합을 선행 조건으로 한다. 병합 순서가 달라지면 구현 PR을 병합하기 전에 최신 `dev` 기준의 다음 미점유 version으로 파일명과 migration 검증을 함께 재정렬한다.

## 인수 조건

- 목적 카탈로그의 모든 활성 목적이 하나의 확정 원 사건·수신자·자원 버전과 연결된다.
- 현재 없는 source 상태는 알림 작업을 만들지 않으며 다른 활성 목적을 막지 않는다.
- 같은 원 사건을 병렬·반복 기록해도 하나의 논리 알림에 수렴한다.
- 직접 방문 완료와 QR 체크인은 `RESERVATION_VISIT_COMPLETED` 하나로 수렴하고, 매장 운영자가 직접 확정한 `NO_SHOW`만 `RESERVATION_NO_SHOW`를 만든다.
- 두 예약 종결 목적은 환불·몰취·귀책·결제 결과를 주장하지 않고 보호된 예약 상세 route가 활성화되기 전까지 `action=null`이다.
- 예약 종결 상태가 확정되면 같은 예약의 아직 미발송된 확정·변경·방문 안내를 대체하되 이미 전달된 이력 의미는 수정하지 않는다.
- 같은 논리 멱등 식별과 다른 사건 내용 fingerprint는 최초 작업을 유지하고 `NOTIFICATION_002`로 거절하며, 동기 producer 명령은 HTTP 409를 반환하고 해당 원 업무 트랜잭션을 rollback한다.
- 같은 사건·내용을 다른 `correlationId`로 반복 기록하면 기존 작업과 `duplicate=true`로 수렴하며 producer 명령을 rollback하지 않는다.
- 원 상태 변경·취소·만료와 역순 사건 뒤 최신 유효 작업만 전달 가능하다.
- Pickup 확정·취소 사건은 Pickup이 직접 기록하고 Notification은 `PickupNotificationSource`로 최신 상태·수신자 관계를 검증하며 MenuHold → Pickup 역방향 조회를 만들지 않는다.
- Waiting 목적은 `WAITING_TEAM`과 불변 수신자 관계에 결속되고, 입장 임박은 팀별 1회 비상태 사건이며 호출·취소·미응답·입장 완료·매장 종료는 각각 다른 목적과 상태 사건을 사용한다.
- 실제 호출 목적은 중앙 `calledAt`과 정확히 10분 뒤 `arrivalDeadline`을 사용한다. 예약 전환 중인 입장 임박 작업은 retry budget 소진 없이 조건부 보류하고, 실패로 같은 `WAITING`에 복귀한 팀의 최초 작업은 유지한다. 상태 사건 재판정과 Worker 보류의 두 실행 순서 모두 작업 version fencing으로 수렴하며, 사건 처리 누락은 유한한 주기 재조회가 회수한다. 더 최신 실제 호출·`ARRIVED`·예약 전환 완료 또는 다른 종결 상태 뒤 오래된 호출·입장 임박 작업은 전달하지 않는다.
- 본인 알림 이력은 `IN_APP` 전달 성공 항목만 고정 정렬·20/50 cursor 계약으로 조회되고 타인 이력, 내부 작업 상태와 금지 필드가 노출되지 않는다.
- 기능 배포 전 공개 전달 완료 알림은 읽음으로 backfill되고 이후 새 전달만 미확인 개수에 포함된다. 개별·전체 읽음은 본인 공개 알림에만 멱등하게 적용되며 page cache가 아니라 MySQL 집합에서 개수를 계산한다.
- 새 전달, 개별 읽음과 전체 읽음 경합은 account 변경 상태 잠금과 조건부 `readAt` 갱신으로 직렬화되고 알림별 상태·미확인 집계·단조 `changeVersion`이 일치한다.
- 알림 SSE 최초 연결·재연결은 `notifications.changed` 뒤 본인 HTTP 이력 재조회로 MySQL 최신 상태에 수렴하고, 다른 audience·계정 cursor를 재사용할 수 없다.
- 읽음 변경 SSE도 `data: {}`를 유지하고 client가 알림 이력과 미확인 개수를 다시 조회한다. 반복 읽음 no-op과 내부 작업 상태는 공개 신호가 아니다.
- SSE 신호 유실·중복·역순과 Valkey 중단은 알림 작업이나 원 거래 상태를 변경하지 않으며, keepalive와 내부 작업 상태는 공개 업무 event가 아니다.
- 알림 실패·열람·침묵이 예약 변경이나 메뉴 대체 동의로 해석되지 않는다.
- OpenAPI 단독 파싱, 참조 해석과 consumer entrypoint 조합이 성공한다.

## 공동 리뷰 게이트

- Reservation 소유자는 목적별 원 상태, `ReservationNotificationSource`의 버전·수신자 결속과 취소·변경·방문 완료·노쇼 대체 규칙, 직접 완료와 QR의 단일 목적 수렴을 검토한다.
- MenuHold 소유자는 메뉴 이행 위험·대체 제안과 결과 사건, `MenuHoldNotificationSource`의 허용 자원 경계를 검토한다.
- Pickup 소유자는 픽업 확정·취소 사건, `PickupNotificationSource`의 버전·수신자 결속과 MenuHold 역방향 의존 금지를 검토한다.
- Waiting 소유자는 상태 사건의 `eventSequence = version + 1`, 팀별 입장 임박 유일성, 예약 전환 실패 복귀 시 최초 사건 유지, 상태 사건 `PUBLISHED` 전 재판정과 `WaitingNotificationSource`의 수신자·목적별 상태 재검증·호출 제한 시각을 검토한다.
- Consumer/API 검토자는 `/api/v1/consumers/me/notifications`와 `/api/v1/consumers/me/notification-events`, fetch streaming Bearer 인증, 공통 오류 envelope와 두 cursor의 서로 다른 실패 의미를 검토한다.
- Consumer/API 검토자는 미확인 개수·개별 읽음·전체 읽음의 principal 결속, `NOTIFICATION_003` 비열거 오류, 멱등 응답과 Backend 선배포 순서를 함께 검토한다.
- Frontend 검토자는 목적·필수 `deliveredAt`·nullable action과 `availability`만으로 전달 성공 이력을 표시하고 오래된 행동을 안전하게 비활성화할 수 있는지 검토한다.
- Frontend 검토자는 consumer shell의 탭당 단일 SSE 연결, 세션별 query 격리, 배지 0·1·99·100+ 접근성, 행동 없는 Waiting 읽음과 읽음 실패 시 상세 이동 비차단을 검토한다.
- 리뷰는 Notification 목적과 MenuHold·Waiting 정책을 다시 소유하지 않는다. 각 소비·제공 경계의 구현 가능성과 기존 계약 충돌만 확인한다.
