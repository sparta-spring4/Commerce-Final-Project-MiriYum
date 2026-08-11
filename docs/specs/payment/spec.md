# 기능 명세: 결제·환불 기반 계약

> 문서 상태: #236 계약 활성화 설계 승인
> 적용 단계: 고도화
> 도메인 소유자: Payment — @usersy628
> 협업 검토: Reservation, Consumer
> 관련 정책 ID: PAY-001~PAY-011, PAY-013~PAY-015, RES-006, RES-009, RES-014
> OpenAPI: `docs/specs/payment/openapi.yaml`
> 최종 승인일: 2026-08-11

## 목표와 범위

Payment는 MiriYum 내부 결제·시도·환불 원장과 PortOne V2 어댑터를 소유한다. Reservation은 Payment의 공개 Service·DTO·오류만 사용하며 Payment Entity·Repository 또는 PortOne 모델에 접근하지 않는다.

### 포함

- 예약금 결제 준비와 고객사 채번 PortOne `paymentId`
- 브라우저 확정 요청과 검증된 Webhook이 합류하는 단일 멱등 확정 경로
- PortOne 서버 조회를 통한 상태·금액·통화·내부 주문 매핑 검증
- 결제·결제 시도·환불의 추가 전용 원장과 결과 불명확 격리
- Reservation이 소비하는 준비·확정·환불 공개 Service 계약
- 일반 사용자 본인의 결제 상세와 결제·환불 이력 조회
- 로그·응답·감사에서 API Secret, Webhook Secret, 결제수단 원문과 토큰 원문 제외

### 제외

- Reservation 수용량·상태·MenuHold의 직접 변경
- 임시 선점과 예약 생성·취소 HTTP 조정
- 브라우저 PortOne SDK 화면과 frontend 구현
- 특정 PG 채널·결제수단의 운영 활성화
- 실제 식당 정산 `PAY-012`, 차지백 운영 자동화와 플랫폼 운영자 수동 복구 UI

## 소유권과 의존 방향

```text
Consumer → Reservation HTTP 조정자
                    ↓ Payment 공개 Service
              Payment 원장·PortOne V2

Consumer → Payment 확정·본인 조회 HTTP
PortOne  → Payment Webhook HTTP
                    ↓
              같은 멱등 확정 경로
```

- Reservation은 임시 선점과 서버가 확정한 예약금 스냅샷을 만든 뒤 `PaymentService.prepareReservationDeposit(...)`를 호출한다.
- Payment는 Reservation을 역조회하거나 Reservation Entity·Repository를 참조하지 않는다. 준비 명령에 포함된 소유자·금액·통화·만료·정책 버전 스냅샷을 원장에 보존한다.
- 별도의 브라우저용 결제 준비 HTTP API는 만들지 않는다. 예약 조정 응답이 Payment 준비 DTO를 포함하는 계약은 #238의 Reservation OpenAPI가 소유한다.
- Payment는 결제 확정·본인 조회·PortOne Webhook HTTP를 소유한다.
- Payment는 검증된 결제 결과 DTO만 반환한다. Reservation의 최종 확정 또는 보상 전이는 Reservation이 소유한다.

## 식별자와 신뢰 경계

| 식별자 | 채번·소유자 | 관계와 공개 규칙 |
| --- | --- | --- |
| `paymentId` | MiriYum Payment | 내부 DB PK와 별도로 채번한 공개 전용 양수 숫자 문자열 참조이며 본인 조회와 Payment HTTP path에 사용한다. 클라이언트는 불투명 값으로 취급한다. |
| `paymentOrderId` | MiriYum Payment | 내부 주문 식별자다. 하나의 `paymentId`와 일대일이며 외부 응답에 노출하지 않는다. |
| `portOnePaymentId` | MiriYum Payment | `paymentOrderId`에서 결정적으로 파생한 고객사 채번 PortOne 식별자다. 준비 응답과 확정 요청의 조회 선택자로만 사용한다. |
| `transactionId` | PortOne | 한 `portOnePaymentId` 아래 시도별로 달라질 수 있다. 인증된 서버 조회 또는 검증된 Webhook에서 얻은 값만 저장한다. |
| `sourceReferenceId` | Reservation | Payment가 해석하지 않는 예약 선점·거래 연결 참조다. 공개 이력에서는 `reservationReferenceId`로만 반환한다. |
| `refundId` | MiriYum Payment | 내부 DB PK와 별도로 채번한 공개 전용 환불 양수 숫자 문자열 참조이며 환불 명령과 원장을 식별한다. 클라이언트는 불투명 값으로 취급한다. |

브라우저가 보낸 성공 여부, 금액, 통화, `transactionId`, 환불 완료 주장은 모두 신뢰하지 않는다. 확정 요청은 path의 MiriYum `paymentId`와 본문의 준비된 `portOnePaymentId`만 전달하며, 서버는 인증 principal의 소유권과 원장 매핑을 확인한 뒤 PortOne V2 API를 조회한다.

## 공개 Service 계약

공개 Service의 명령과 결과는 scalar·enum·record 기반 DTO만 사용하고 JPA Entity, Repository, PortOne SDK·API 타입을 노출하지 않는다.

### 예약금 준비

```java
PaymentPreparation prepareReservationDeposit(PrepareReservationDepositCommand command)
```

`PrepareReservationDepositCommand`는 다음 필드를 가진다.

- `sourceReferenceId`: Reservation이 소유한 불투명 거래 연결 참조
- `consumerAccountId`: 인증된 일반 사용자 계정 ID
- `amountMinor`: 서버가 계산한 최소 통화 단위의 양수 정수
- `currency`: ISO 4217 통화 코드
- `sourceExpiresAt`: 임시 선점의 중앙 만료 시각
- `sourcePolicyVersion`: 예약금 계산 정책의 known version
- `idempotencyKey`: 예약 조정 명령과 결제 준비를 연결하는 멱등 키

`PaymentPreparation`은 `paymentId`, `portOnePaymentId`, `orderName`, `amountMinor`, `currency`, `sourceExpiresAt`, `status=READY`만 반환한다. Store ID, Channel Key와 활성 결제수단은 배포 환경의 공개 frontend 설정이며 이 DTO가 API Secret이나 Webhook Secret을 반환하지 않는다.

같은 source·멱등 키·지문은 기존 결과를 반환한다. 같은 멱등 키의 다른 지문, 만료됐거나 금액이 0 이하인 source, 같은 source의 다른 활성 결제는 실패 폐쇄한다.

### 결제 확정

```java
PaymentResult confirmPayment(ConfirmPaymentCommand command)
```

`ConfirmPaymentCommand`는 `paymentId`, `portOnePaymentId`, 인증된 `consumerAccountId`, `idempotencyKey`만 가진다. Payment는 `paymentId + consumerAccountId` 소유 범위에서 결제를 먼저 조회해 실제 부재와 타인 소유를 모두 `PAYMENT_001` 404로 숨긴다. 그 뒤 원장의 source·amount·currency·PortOne 매핑을 검증하고 중앙 `CONFIRMING` 임대를 조건부 선점한 뒤 PortOne V2 결제를 조회한다.

- 인증된 조회가 `PAID`이고 금액·통화·내부 매핑이 모두 일치할 때만 `PAID`를 확정한다.
- 명시적인 실패·취소·결제 없음은 시도 원장에 종결 결과를 기록하고 결제를 `READY`로 복원할 수 있다.
- 조회 시간 초과·응답 불명확·대기 상태는 성공이나 실패로 추정하지 않고 `RECONCILIATION_REQUIRED`로 격리한다.
- 이미 `PAID`, `PARTIALLY_REFUNDED`, `REFUNDED`인 결제의 같은 확정 재시도는 외부 조회나 원장을 중복 생성하지 않고 기존 결과를 반환한다.

### 환불

```java
RefundResult requestRefund(RequestRefundCommand command)
```

이 계약은 Reservation 같은 소유 업무 도메인의 서버 내부 조정자만 사용하며 일반 사용자용 직접 환불 HTTP를 만들지 않는다. 명령은 `paymentId`, `sourceEventId`, `refundAmountMinor`, `reasonCode`, `policyVersion`, `idempotencyKey`를 가진다. Payment는 원 승인 스냅샷과 완료된 누적 환불을 기준으로 잔액을 검증하고, 결과 불명확 상태에서는 새 외부 환불을 발행하지 않는다.

`RefundResult`는 `refundId`, `paymentId`, 요청·완료·누적·잔여 금액, 통화, 공개 환불 상태와 시각만 반환한다. 환불 완료 뒤에도 과거 원장 행을 수정·삭제하지 않고 반대·보정 원장을 추가한다.

### 본인 조회

```java
PaymentResult getOwnedPayment(String paymentId, String consumerAccountId)
PaymentHistorySlice getConsumerPaymentHistory(PaymentHistoryQuery query)
```

ID 단독 조회 뒤 소유권을 다시 조회하지 않는다. `paymentId + consumerAccountId`로 한 번에 조회하고 실제 부재와 타인 소유를 같은 `PAYMENT_001` 404로 숨긴다.

이력은 `createdAt DESC, paymentId DESC`의 안정 정렬과 불투명 cursor를 사용한다. 페이지 크기는 공통 `Size` query parameter의 `size`를 재사용하며 기본 20, 최대 100이다. cursor와 결제 전용 slice는 현재 Payment만 소비하므로 도메인 계약에 유지하고, 두 번째 소비 도메인이 생기기 전에는 공통 계약으로 승격하지 않는다. cursor에는 정렬 경계와 필터 지문을 위변조 방지 형식으로 담고 원문 ID 목록이나 개인정보를 넣지 않는다. 잘못됐거나 다른 필터에 재사용한 cursor는 `PAYMENT_005` 400으로 거부한다.

## 공개 HTTP 계약

| 사용자 목적 | API | 인증·멱등 |
| --- | --- | --- |
| 결제 확정 요청 | `POST /api/v1/consumers/payments/{paymentId}/confirmations` | Consumer Access JWT, `Idempotency-Key` 필수 |
| 본인 결제 상세 | `GET /api/v1/consumers/payments/{paymentId}` | Consumer Access JWT |
| 본인 결제·환불 이력 | `GET /api/v1/consumers/payments` | Consumer Access JWT, cursor pagination |
| PortOne Webhook | `POST /api/v1/payments/webhooks/portone` | Access JWT 없음, Webhook signature·원문 body 검증 |

확정 요청 본문은 `portOnePaymentId` 외의 상태·금액·통화·`transactionId`를 받지 않는다. 확정 동기 조회가 최종 결론을 내리지 못하면 HTTP 202와 `RECONCILIATION_REQUIRED` 상태를 반환하며 완료로 표시하지 않는다.

Webhook은 PortOne V2 최신 `2024-04-25` body를 수신하고 Standard Webhooks 서명을 raw body 기준으로 검증한다. 공통 필드 `type`, `timestamp`, `data.storeId`를 먼저 검증한다. 현재 처리 allowlist는 결제 상태용 `Transaction.Paid`, `Transaction.Failed`, `Transaction.PayPending`과 취소·환불 상태용 `Transaction.PartialCancelled`, `Transaction.Cancelled`, `Transaction.CancelPending`이다. 결제 type은 `data.paymentId`, `data.transactionId`를 필수로 요구하고 취소·환불 type은 `data.cancellationId`도 필수로 요구한다. 지원 type에서 필수 식별자가 누락되면 `COMMON_001` 400으로 거부하고 원장을 변경하지 않는다. 서명이 유효하지만 allowlist 밖의 알려진 또는 미래 type은 추가 필드를 허용한 채 거래를 변경하지 않고 200으로 무시해 제공자의 무한 재시도를 막는다. 지원 type은 알려진 `portOnePaymentId`를 서버 API로 다시 조회해 같은 확정 경로를 호출한다. 서명 검증 실패 요청은 거래를 변경하지 않는다.

## 상태와 전이

### 결제 공개 상태

| 상태 | 의미 |
| --- | --- |
| `READY` | 준비 완료 또는 인증된 비성공 종결 뒤 재시도 가능 |
| `CONFIRMING` | 한 처리자가 PortOne 서버 조회와 내부 확정을 수행 중 |
| `PAID` | 서버 검증된 결제 성공과 원장 기록 완료 |
| `PARTIALLY_REFUNDED` | 일부 환불 완료, 환불 가능 잔액 존재 |
| `REFUNDED` | 환불 가능 잔액 전부 환불 완료 |
| `RECONCILIATION_REQUIRED` | 결과 불명확 또는 계약 불일치로 신규 청구·환불을 차단한 격리 상태 |

`PAID` 이후의 결제를 `READY`로 되돌리지 않는다. `PARTIALLY_REFUNDED`와 `REFUNDED`는 새 결제 성공 이벤트로 되돌릴 수 없다. 과거·중복 Webhook은 상태 버전 조건에 실패하면 성공적으로 무시하고 감사만 남긴다.

### 환불 공개 상태

`REQUESTED`, `VALIDATING`, `PROCESSING`, `COMPLETED`, `FAILED`, `RECONCILIATION_REQUIRED`를 사용한다. 명시적 실패만 `FAILED`로 종결하고 외부 결과를 모르면 `RECONCILIATION_REQUIRED`를 유지한다.

### 마지막 결제 시도 공개 상태

`NOT_STARTED`, `PENDING`, `PAID`, `FAILED`, `CANCELLED`, `UNKNOWN`을 사용한다. 결제 aggregate가 재시도 가능한 `READY`여도 `lastAttemptStatus`로 최초 준비와 명시적 실패·취소를 구분한다. 제공자 오류 코드·문구와 `transactionId`는 공개하지 않는다.

## 공개 응답

결제 상세·이력 항목은 다음 필드만 공개한다.

- `paymentId`, `reservationReferenceId`
- `amountMinor`, `refundedAmountMinor`, `refundableAmountMinor`, `currency`
- 결제 `status`, `lastAttemptStatus`, `createdAt`, nullable `paidAt`, nullable `updatedAt`
- `refunds`: `refundId`, 금액, 상태, 요청·완료 시각의 배열

`paymentOrderId`, `transactionId`, PortOne 원문 응답, 결제수단 상세, 카드·계좌·휴대전화 원문, API Secret, Webhook Secret과 내부 대사 메모는 공개하지 않는다. 빈 이력은 `items: []`, `nextCursor: null`, `hasNext: false`다.

## 오류 코드

| 외부 코드 | HTTP | 의미 |
| --- | --- | --- |
| `PAYMENT_001` | 404 | 본인 범위에서 결제를 찾을 수 없음 |
| `PAYMENT_002` | 409 | 현재 결제·환불 상태에서 요청한 전이 불가 |
| `PAYMENT_003` | 409 | 서버 조회로 확인한 source, 금액, 통화 또는 PortOne 매핑 불일치 |
| `PAYMENT_004` | 409 | 같은 source에 다른 활성 결제가 존재함 |
| `PAYMENT_005` | 400 | 이력 cursor가 잘못됐거나 현재 필터와 일치하지 않음 |
| `PAYMENT_006` | 401 | PortOne Webhook 서명·timestamp 검증 실패 |
| `PAYMENT_007` | 409 | 환불 요청액이 검증된 환불 가능 잔액을 초과함 |
| `PAYMENT_008` | 409 | 결제 준비 source가 이미 만료됐거나 확정 불가 상태임 |

PortOne 조회 장애나 결과 불명확은 거짓 4xx·최종 실패로 변환하지 않고 202 상태 응답과 대사 상태로 보존한다. 기술 공통 입력·인증·속도 제한 오류와 같은 `Idempotency-Key`의 다른 요청 지문 충돌은 공통 오류 계약의 `COMMON_007`을 사용한다.

## 원장·동시성·복구

- 결제, 시도, 환불, Webhook 수신, 상태 전이와 대사 작업을 별도 추가 전용 기록으로 보존한다.
- 준비 멱등 키·요청 지문, source 활성 결제, `portOnePaymentId`, 시도별 `transactionId`, Webhook 메시지 식별 조합, 환불 source event·멱등 키를 DB 유일 제약과 조건부 전이로 보호한다.
- 외부 네트워크 호출을 긴 DB 트랜잭션 안에 유지하지 않는다. `CONFIRMING` 작업 임대와 상태 버전으로 단일 처리자를 선점하고, 결과 저장은 인증된 조회 스냅샷에 연결한다.
- 오래된 `CONFIRMING`과 `RECONCILIATION_REQUIRED`는 중앙 작업자만 임대를 인수해 알려진 `portOnePaymentId`를 재조회한다. 자동 해소할 수 없는 건은 PAY-014·PAY-015로 보낸다.
- Reservation은 Payment 성공 DTO를 받은 뒤 자신의 상태를 확정한다. 그 뒤 Reservation 확정이 실패하면 별도의 멱등 환불 obligation을 Payment에 요청하며 Payment가 Reservation 상태를 직접 바꾸지 않는다.

## Migration·호환성

- 기존 1차 MVP 예약과 비금전 취소 V1에는 Payment 행을 backfill하지 않는다.
- 신규 Payment 테이블은 기존 예약 PK나 상태 enum의 의미를 변경하지 않고 scalar source reference로 연결한다.
- Flyway 번호는 실제 구현 PR을 최신 `dev`에서 시작할 때 다시 확인한다. 이 계약 PR은 migration을 추가하지 않는다.
- 기존 `/api/v1/reservations` 즉시 확정 계약은 예약금 미사용 매장에 유지한다. 예약금 활성 흐름의 Reservation 요청·응답 변경은 #238에서 별도 명세와 OpenAPI로 활성화한다.

## 검증 계약

- Service 단위 계약: 준비 지문, 소유권, 금액·통화, 상태 전이와 환불 잔액
- MockMvc 계약: 확정 요청 최소 body, 본인 존재 은닉, cursor 경계, 202 대사 응답, secret 비노출
- MySQL 통합 계약: 중복 준비·확정·Webhook·환불, 상태 버전 경합, 원장 추가 전용성과 재시작 복구
- PortOne adapter 계약: `paymentId` 조회, `PAID` 검증, 실패·대기·timeout 분리, 최신 Webhook 서명 검증, 지원 type별 필수 식별자와 미지원 type 200 무시
- sandbox 검증은 자격 증명과 도달 가능한 Webhook 환경이 구성된 경우에만 별도 증거로 기록하며 unit·mock 성공을 sandbox 성공으로 표현하지 않는다.

## 인수 조건

- 브라우저 성공 응답만으로 결제를 확정하지 않는다.
- 같은 결제·Webhook·환불 재시도가 외부 호출과 원장을 중복 확정하지 않는다.
- 결과 불명확 거래는 신규 청구·환불에서 격리되고 대사 전 거짓 완료·실패로 표시되지 않는다.
- Reservation과 Consumer는 Payment Entity·Repository 없이 공개 Service·DTO·오류만 사용한다.
- 일반 사용자는 본인의 최소 결제·환불 이력만 안정적인 cursor로 조회한다.
- 로그·DB 공개 필드·응답에 PortOne secret, 결제 토큰과 결제수단 원문이 남지 않는다.

## 외부 계약 근거

- [PortOne V2 인증 결제 연동](https://developers.portone.io/opi/ko/integration/start/v2/checkout?v=v2)
- [PortOne V2 Webhook 연동](https://developers.portone.io/opi/ko/integration/webhook/readme-v2?v=v2)
- [PortOne V2 결제 취소](https://developers.portone.io/opi/ko/integration/cancel/v2/readme)
