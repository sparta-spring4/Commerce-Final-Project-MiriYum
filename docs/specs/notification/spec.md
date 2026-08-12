# 기능 명세: 알림 계약

> 적용 단계: `고도화`
> 소유 Issue: `#247`
> 구현 선행: 이 계약 PR의 `dev` 병합 뒤 `#248`, `#249`, `#251` 순서로 활성화한다.

## 관련 정책 ID

- 알림: `NOTI-001`~`NOTI-010`, 특히 `NOTI-004`~`NOTI-009`
- 예약: `RES-013`, `RES-014`
- 메뉴 홀드: `HOLD-009`, `HOLD-011`
- 데이터·API: `docs/07-data-and-api-contracts.md`

## 범위

### 포함

- Reservation·MenuHold·Pickup의 확정 사건에서 생성하는 1차 알림 목적 카탈로그
- 원 사건을 Notification에 기록하는 내부 공개 Service·DTO·오류 의미
- 렌더링과 행동 유효성 재검증을 위한 원 도메인 공개 조회 경계
- Notification 소유의 소비자 본인 알림 이력 HTTP API와 cursor 계약
- 최신 상태에 의한 미발송 작업 취소·만료·대체 규칙

### 제외

- 일반 메뉴 품절, 일반 메뉴 추천, 주변 대체 매장 추천, 가게 찜, 광고·판촉 알림
- Payment·환불·Waiting·체크인·노쇼·취소 자리 승계 목적과 원 사건
- SMS·알림톡·푸시·이메일 provider와 실제 연락처 조회
- frontend 구현, Notification runtime·migration·worker 구현
- `NOTI-009`의 정확한 보관기간과 법률 문구

## 사용자 관점의 기능 동작

- 로그인한 일반 사용자는 자신에게 생성되어 현재 보관 중인 논리 알림만 조회한다.
- 알림은 원 거래 상태를 설명할 뿐 예약·취소·메뉴 대체의 성립 조건이 아니다.
- 사용자는 알림에서 허용된 예약·픽업·대체 메뉴 검토 화면으로 이동할 수 있다. 행동이 만료·취소·대체됐으면 화면 이동 전 API가 반환한 중앙 상태를 다시 확인한다.
- 일반 품절은 목록·상세 화면의 현재 재고 상태로 확인한다. 기존 확정 거래가 실제 영향을 받은 경우에만 영향 알림을 받는다.

## 1차 목적 계약

목적 코드, 필수성, 원 사건과 기본 행동의 정본은 `docs/service-policies/16-notification.md`의 `1차 Reservation·MenuHold·Pickup 알림 목적 카탈로그`다. 이 명세와 OpenAPI는 해당 코드를 그대로 사용하며 별칭이나 frontend 전용 목적을 만들지 않는다.

- 1차 허용 채널은 `IN_APP` 하나다. 알림 이력에 조회 가능하게 내구성 기록한 시점을 `IN_APP` 전달 성공으로 기록한다. 전달 전 취소·최종 실패·대기 작업은 내부 작업·감사 상태로만 유지하고 공개 이력에 포함하지 않는다.
- 목적 카탈로그에 대응하는 원 상태·사건이 아직 활성화되지 않았으면 그 목적은 작업을 만들지 않는다. 다른 활성 목적의 구현과 완료를 막지 않는다.
- 방문 안내는 원 예약의 `startAt`, `timeZoneId`, 활성 `timingPolicyVersion`으로 계산한 `scheduledAt`을 사용한다. 정확한 선행 시간은 정책 버전이 소유하며 이벤트 생산자나 worker가 임의 숫자를 사용하지 않는다.

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
| `sourceDomain` | `RESERVATION`, `MENU_HOLD`, `PICKUP` | 원 사건 소유 도메인 |
| `purpose` | 목적 카탈로그 enum | 원 상태와 일치하지 않으면 기록 거부 |
| `recipientAccountId` | 양의 public ID string | 대표 일반 사용자 계정 하나 |
| `recipientRelationVersion` | 양의 64-bit integer | 원 자원과 수신자 관계 버전 |
| `resourceType` | `RESERVATION`, `MENU_HOLD`, `PICKUP_RESERVATION`, `MENU_SUBSTITUTION_PROPOSAL` | 행동과 최신 상태 재검증의 기준 |
| `resourceId` | 양의 public ID string | 원 자원 식별자 |
| `resourceVersion` | 양의 64-bit integer | 원 상태·제안 버전 |
| `sourceState` | non-blank string, 최대 64 | 목적 카탈로그의 허용 원 상태 |
| `occurredAt` | offset 포함 date-time | 원 사건 중앙 발생 시각이자 이력 정렬 1차 키 |
| `scheduledAt` | offset 포함 date-time | 즉시는 `occurredAt`, 예약 목적은 정책 계산 결과 |
| `expiresAt` | nullable offset date-time | 행동 또는 예약 작업 만료 시각. 원 권리의 기한을 연장하지 않음 |
| `timingPolicyVersion` | nullable 양의 64-bit integer | 예약 목적에 필수, 즉시 목적에는 null |
| `correlationId` | non-blank string, 최대 100 | 원 명령·감사 상관관계 |

사건에는 전화번호·이메일·수신 주소·완성된 제목/본문·동행자·다른 구매자·결제수단·알레르기 원문을 넣지 않는다. `scheduledAt`과 값이 있는 `expiresAt`은 `occurredAt`보다 과거일 수 없다. 예약 목적은 `scheduledAt`·`expiresAt`·`timingPolicyVersion`이 모두 필수이고 `scheduledAt < expiresAt`이어야 한다.

### 논리 멱등 식별

논리 알림은 다음 값의 정규화 결합으로 식별한다.

```text
sourceDomain + sourceEventId + recipientAccountId + purpose + resourceType + resourceId + resourceVersion
```

`correlationId`, 주소, title, worker instance와 채널 시도 ID는 논리 멱등 식별에 참여하지 않는다. 채널 시도는 같은 논리 알림 아래 별도 식별자를 사용한다.

멱등 키가 같을 때 비교할 canonical payload fingerprint는 다음 필드를 RFC 8785 JSON Canonicalization Scheme(JCS)으로 직렬화한 UTF-8 bytes의 SHA-256 lowercase hex다. RFC 8785가 정한 object property 정렬, ECMAScript 문자열 직렬화와 escape 형식을 그대로 사용하며 별도의 pretty-print, `/` escape, 임의 `\uXXXX` 변환이나 escape hex 대소문자 선택을 허용하지 않는다.

- JSON object는 `contractVersion`, `recipientRelationVersion`, `sourceState`, `occurredAt`, `scheduledAt`, `expiresAt`, `timingPolicyVersion`, `correlationId` key를 모두 포함하며 실제 byte 순서는 RFC 8785 property sorting을 따른다.
- `contractVersion`은 문자열 `notification-source-event-v1`이다. `sourceState`와 `correlationId`는 입력 검증을 통과한 원문을 JSON escaping만 적용해 사용하며 trim·대소문자 변경·Unicode 재정규화를 하지 않는다.
- date-time은 UTC로 변환한 뒤 초 단위 `yyyy-MM-dd'T'HH:mm:ss'Z'`로 직렬화한다. 입력에 0이 아닌 초 미만 정밀도가 있으면 기록을 거절해 반올림·절삭 차이를 허용하지 않는다.
- 필수 `scheduledAt`은 항상 정규화한 date-time string으로 넣는다. nullable `expiresAt`, `timingPolicyVersion`은 key를 생략하지 않고 값이 없을 때 JSON `null`로 직렬화한다. 64-bit 값인 `recipientRelationVersion`과 값이 있는 `timingPolicyVersion`은 IEEE-754 정밀도 손실을 피하기 위해 JSON number가 아니라 leading zero 없는 부호 없는 10진 JSON string으로 직렬화한다.
- fingerprint 원문은 위 필드 외 값을 포함하지 않으며 공급자 payload, 완성된 메시지 본문이나 수신 주소를 추가하지 않는다. 작업에는 fingerprint와 `contractVersion`을 함께 저장하고 다른 계약 버전의 fingerprint를 같은 값으로 간주하지 않는다.

## 원 도메인 공개 조회 계약

Notification은 작업 실행과 이력 행동 계산에 필요한 다음 읽기 경계를 소유하고 각 원 도메인은 해당 구현체를 제공한다. 포트 인터페이스와 공통 DTO는 Notification package에 두며, 원 도메인의 구현체는 Notification의 공개 포트만 의존하고 Entity·Repository를 노출하지 않는다. 따라서 기록 호출과 조회 구현의 package 의존은 모두 Reservation·MenuHold·Pickup → Notification 한 방향으로 유지한다.

```text
ReservationNotificationSource.readContext(resourceId, expectedVersion, recipientAccountId)
MenuHoldNotificationSource.readContext(resourceType, resourceId, expectedVersion, recipientAccountId)
PickupNotificationSource.readContext(resourceId, expectedVersion, recipientAccountId)
  -> NotificationSourceContextV1
```

조회 포트와 원 사건 자원 소유는 다음과 같이 고정한다.

| 조회 포트 | 처리하는 `resourceType` |
|---|---|
| `ReservationNotificationSource` | `RESERVATION` |
| `MenuHoldNotificationSource` | `MENU_HOLD`, `MENU_SUBSTITUTION_PROPOSAL` |
| `PickupNotificationSource` | `PICKUP_RESERVATION` |

Pickup은 자신의 픽업 예약 확정·취소 사건을 같은 업무 트랜잭션에서 `NotificationTaskRecorder`에 직접 기록한다. `MenuHoldNotificationSource`는 `PICKUP_RESERVATION`을 처리하지 않으며 MenuHold → Pickup 역방향 의존을 만들지 않는다. 메뉴 이행 위험과 대체 제안·결과의 원 사건은 MenuHold가 소유하고, 연결된 `PICKUP_RESERVATION`은 `actionResourceType`·`actionResourceId`로만 반환할 수 있다.

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

## 상태 대체·만료 계약

- 예약 취소·만료·방문 완료는 같은 예약의 아직 미발송된 확정·변경·방문 안내를 취소한다.
- 예약 변경이 확정되면 이전 버전의 방문 안내를 취소하고 유효한 시점 정책이 있을 때 새 버전 안내를 생성한다.
- `RESERVATION_COORDINATION_REQUIRED`는 최종 변경·취소 또는 원 영향 사건 해소 뒤 행동을 `SUPERSEDED`로 반환한다. 열람·침묵·알림 실패는 조율 동의가 아니다.
- 메뉴 이행 위험 사건은 원래 이행 확정, 대체 제안, 거래 취소 중 최신 결과가 도착하면 이전 미발송 작업을 대체한다.
- 대체 제안은 수락·거절·만료·거래 취소 중 하나가 확정되면 이전 행동을 `SUPERSEDED` 또는 `EXPIRED`로 반환한다. 전달 지연과 열람은 `expiresAt`을 연장하지 않는다.
- 이미 전달된 알림의 내용이 중요하게 바뀌면 기존 이력을 수정해 다른 의미로 만들지 않고 새 상태 버전의 논리 알림을 생성한다.

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
- nullable `action`이 있으면 `type`, `resource`의 `type`·`id`, `availability`, nullable `expiresAt`

`title`은 승인된 template field allowlist로 렌더링한 최대 100자의 안전한 제목이다. 원문 주소·전체 메시지 본문·provider payload·내부 재시도 횟수·감사 메모는 반환하지 않는다.

### 내부 작업 상태와 공개 가시성

Notification 내부 작업은 `PENDING`, `DELIVERED`, `FAILED`, `CANCELLED`를 유지한다. 소비자 공개 이력에는 `IN_APP` 전달이 성공해 `deliveredAt`이 확정된 `DELIVERED` 작업만 노출한다. `PENDING`, `FAILED`, `CANCELLED`는 내부 작업·시도·감사 기록에만 보존하며 공개 응답에 전달 상태 필드를 만들지 않는다.

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

## 오류 계약

| 코드 | 공개 HTTP 또는 내부 결과 | 의미 |
|---|---:|---|
| `NOTIFICATION_001` | 400 | cursor 형식·버전·무결성이 유효하지 않음 |
| `NOTIFICATION_002` | 409 — 원 사건 producer 명령 | 같은 논리 멱등 식별에 저장된 canonical payload와 다른 payload가 기록됨. `ServiceException(NotificationErrorCode.SOURCE_EVENT_CONFLICT)`으로 전파하며 소비자 알림 이력 GET의 오류로 노출하지 않음 |
| `COMMON_001` | 400 | `size` 등 요청 값 검증 실패 |
| `AUTH_001` | 401 | 사용할 수 있는 일반 사용자 인증이 없음 |
| `AUTH_011` | 403 | 현재 계정 상태가 조회를 허용하지 않음 |
| `COMMON_012` | 503 | 이력 중앙 저장소 등 필수 의존성을 일시적으로 사용할 수 없음 |

타 사용자의 계정 ID를 path나 query로 받지 않으며 타인 이력을 찾는 `404` 계약을 만들지 않는다.

## Migration·호환성 요구

- `#248`이 migration 번호, table·index와 runtime package의 정확한 allowlist를 최신 `dev` 기준으로 확정하고 `NotificationErrorCode.SOURCE_EVENT_CONFLICT`와 `NotificationTaskRecorder` 구현을 소유한다. 이 Issue는 runtime이나 migration 파일을 만들지 않는다.
- 목적·source event·cursor는 버전 필드를 가져야 한다. 새 목적과 nullable 필드는 하위 호환 추가만 허용하고 기존 enum 의미를 재사용하지 않는다.
- `NOTI-009` 확정 전에도 보관 만료를 적용할 수 있는 구조를 갖추되 영구 보존이나 임의 삭제 기간을 기본값으로 넣지 않는다.
- 외부 채널 추가는 논리 알림과 `IN_APP` 이력이 공유하는 `notificationId`를 바꾸지 않고 같은 논리 알림 아래 내부 채널 시도만 추가한다.

## 인수 조건

- 목적 카탈로그의 모든 1차 목적이 하나의 확정 원 사건·수신자·자원 버전과 연결된다.
- 현재 없는 source 상태는 알림 작업을 만들지 않으며 다른 활성 목적을 막지 않는다.
- 같은 원 사건을 병렬·반복 기록해도 하나의 논리 알림에 수렴한다.
- 같은 논리 멱등 식별과 다른 fingerprint는 최초 작업을 유지하고 `NOTIFICATION_002`로 거절하며, 동기 producer 명령은 HTTP 409를 반환하고 해당 원 업무 트랜잭션을 rollback한다.
- 원 상태 변경·취소·만료와 역순 사건 뒤 최신 유효 작업만 전달 가능하다.
- Pickup 확정·취소 사건은 Pickup이 직접 기록하고 Notification은 `PickupNotificationSource`로 최신 상태·수신자 관계를 검증하며 MenuHold → Pickup 역방향 조회를 만들지 않는다.
- 본인 알림 이력은 `IN_APP` 전달 성공 항목만 고정 정렬·20/50 cursor 계약으로 조회되고 타인 이력, 내부 작업 상태와 금지 필드가 노출되지 않는다.
- 알림 실패·열람·침묵이 예약 변경이나 메뉴 대체 동의로 해석되지 않는다.
- OpenAPI 단독 파싱, 참조 해석과 consumer entrypoint 조합이 성공한다.

## 공동 리뷰 게이트

- Reservation 소유자는 목적별 원 상태, `ReservationNotificationSource`의 버전·수신자 결속과 취소·변경·방문 완료 대체 규칙을 검토한다.
- MenuHold 소유자는 메뉴 이행 위험·대체 제안과 결과 사건, `MenuHoldNotificationSource`의 허용 자원 경계를 검토한다.
- Pickup 소유자는 픽업 확정·취소 사건, `PickupNotificationSource`의 버전·수신자 결속과 MenuHold 역방향 의존 금지를 검토한다.
- Consumer/API 검토자는 `/api/v1/consumers/me/notifications`, 공통 인증·오류 envelope와 cursor 실패 의미를 검토한다.
- Frontend 검토자는 목적·필수 `deliveredAt`·nullable action과 `availability`만으로 전달 성공 이력을 표시하고 오래된 행동을 안전하게 비활성화할 수 있는지 검토한다.
- 리뷰는 Notification 목적과 MenuHold 정책을 다시 소유하지 않는다. 각 소비·제공 경계의 구현 가능성과 기존 계약 충돌만 확인한다.
