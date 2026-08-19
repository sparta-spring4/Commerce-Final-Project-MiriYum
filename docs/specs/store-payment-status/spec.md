# 매장 운영자 예약 결제 상태 조회 기능 명세

> 상태: Issue #273 contract-first 활성 명세
> Runtime: Issue #273 branch에서 구현·focused 검증 전에는 미완료
> 소유자: Reservation HTTP 조합 `@116Lv`; Payment·Reservation 원장 소유자 공동 검토

## 목적과 범위

인증된 매장 운영자는 자기 매장의 예약 상세에서 저장된 결제·환불·대사 상태를 최소 필드로 조회한다. 대상은 `GET /api/v1/store-operators/stores/{storeId}/reservations/{reservationId}/payment-status` 하나다. 환불 요청·승인·실행, provider 조회, 대사 실행, 결제수단·provider 원문, 매장 결제 목록, frontend는 제외한다.

정본 정책은 `PAY-002`, `PAY-005`, `PAY-009`, `PAY-014`와 Reservation의 V1/V2 예약금 계약이다. Payment 원본 상태와 의미는 [Payment 명세](../payment/spec.md), Reservation의 최종 예약·deposit process 연결은 [Reservation 명세](../reservation/spec.md)를 따른다.

## 소유권과 호출 경계

Reservation이 nested store-reservation HTTP use-case를 소유한다. 처리 순서는 다음과 같다.

1. Store 공개 Service가 활성 운영자 계정을 확인하고 `(storeId, operatorAccountId)`를 한 번에 조회한다.
2. 매장 부재와 다른 운영자 소유 매장은 모두 `STORE_001` 404다.
3. Reservation은 `(reservationId, storeId)`로 자기 Entity를 조회한다. 부재와 다른 매장 예약은 모두 `RESERVATION_001` 404다.
4. V1 또는 비금전 예약은 Payment를 호출하지 않고 `NOT_APPLICABLE`을 반환한다.
5. V2 예약은 Reservation 소유 deposit process link에서 `paymentId`와 `reservationHoldId` scalar를 얻는다.
6. Payment 공개 Service는 `paymentId`, 정확한 source type `RESERVATION_DEPOSIT`, expected `reservationHoldId` source reference를 모두 대조해 최소 immutable snapshot을 반환한다.
7. Reservation이 snapshot을 HTTP DTO로 조합한다.

Reservation은 Payment Entity·Repository를, Payment는 Reservation Entity·Repository·Service를 참조하지 않는다. Controller도 Repository를 직접 호출하지 않는다. 기존 `Reservation -> Payment` 의존 방향을 유지한다.

## HTTP와 조회 의미

Store Operator Access JWT가 필요하다. `storeId`와 `reservationId`는 양수 공개 ID다. 단일 예약에 활성 예약금 Payment가 최대 하나이므로 query filter, cursor, page, size를 받지 않는다. 향후 목록 수요는 별도 collection 계약으로 다룬다.

이 GET은 MySQL에 저장된 상태만 읽는다. PortOne 호출, 환불 생성, 대사 claim, 상태 전이와 원장 쓰기를 하지 않는다. 결과 불명은 정상적으로 조회 가능한 상태이므로 HTTP 200이며 202가 아니다.

## 응답

top-level data는 `reservationId`, `result`, `reconciliationRequired`, `observedAt`, nullable `payment`을 가진다. `observedAt`은 서버가 조회 중 한 번 읽은 UTC 시각이다.

Payment snapshot은 다음만 포함한다.

- `paymentId`
- `amountMinor`, `refundedAmountMinor`, `refundableAmountMinor`, `currency`
- Payment `status`, `lastAttemptStatus`
- `createdAt`, nullable `paidAt`, `updatedAt`
- 환불별 `refundId`, `amountMinor`, `status`, `requestedAt`, nullable `completedAt`

금액은 통화 최소단위 정수이고 통화는 ISO 4217 대문자 3자다. 환불은 `requestedAt ASC, refundId ASC`의 안정 순서다. 결제수단, consumer ID, source reference, PortOne payment/transaction/cancellation ID와 provider 응답은 포함하지 않는다.

## 운영자 요약 상태

`result`는 원본 상태를 덮어쓰지 않는 읽기 projection이다.

| result | 조건 |
| --- | --- |
| `NOT_APPLICABLE` | V1 또는 비금전 예약. `payment`는 null이다. |
| `AWAITING_PAYMENT` | Payment가 `READY`이고 최근 attempt가 `NOT_STARTED`다. |
| `UNKNOWN` | Payment가 `RECONCILIATION_REQUIRED`, 최근 attempt가 `UNKNOWN`, 또는 refund가 `RECONCILIATION_REQUIRED`다. |
| `PROCESSING` | Payment가 `CONFIRMING`, 최근 attempt가 `PENDING`, 또는 refund가 `REQUESTED`, `VALIDATING`, `PROCESSING`이다. |
| `FAILED` | 최근 attempt가 `FAILED` 또는 `CANCELLED`, 또는 refund가 `FAILED`다. |
| `COMPLETED` | 위 조건이 없고 Payment가 `PAID`, `PARTIALLY_REFUNDED`, `REFUNDED`다. |

판정 우선순위는 `UNKNOWN -> PROCESSING -> FAILED -> COMPLETED`다. `PARTIALLY_REFUNDED`도 미종결 refund가 없으면 현재까지 확정된 결과이므로 `COMPLETED`이고 정확한 누적 환불액과 환불 가능 잔액으로 구분한다. `reconciliationRequired`는 `result == UNKNOWN`과 일치한다. 계약과 다른 상태 조합은 성공으로 추정하지 않고 `COMMON_012` 503이다.

## 오류와 은닉

| HTTP/code | 의미 |
| --- | --- |
| 400 `COMMON_001` | path ID가 양수가 아님 |
| 401 공통 인증 오류 | Access JWT가 없거나 유효하지 않음 |
| 403 공통 권한 오류 | Store Operator token namespace가 아님 또는 현재 계정 상태가 요청 불가 |
| 404 `STORE_001` | 매장이 없거나 현재 운영자 소유가 아님 |
| 404 `RESERVATION_001` | 권한이 확인된 매장 범위에 예약이 없음 |
| 503 `COMMON_012` | V2 link/Payment source가 누락·불일치하거나 저장 상태 조합이 계약과 다름 |

Payment의 내부 lookup 부재는 이 HTTP에서 `PAYMENT_001`로 노출하지 않는다. 저장된 결과 불명은 오류가 아니라 `200 + UNKNOWN`이다.

## Migration·호환성

V30의 `payments.payment_id`, `payments(source_type, source_reference_id)`와 V55의 `reservation_deposit_processes.payment_id`, `reservation_deposit_processes.final_reservation_id` unique index를 사용한다. 읽기 API에 새 테이블·컬럼·인덱스가 필요하지 않으므로 migration을 만들지 않는다. 기존 Consumer Payment HTTP와 Reservation 상세 응답은 변경하지 않는다.

## 인수 조건과 검증

- 타 매장과 다른 예약의 Payment 존재를 구분해 노출하지 않는다.
- 결과 불명·환불 처리 중·명시 실패를 완료로 표시하지 않는다.
- 기존 V1/비금전 예약은 `NOT_APPLICABLE`이며 결제 실패가 아니다.
- 정확한 금액·통화와 canonical Payment/refund 상태만 최소 DTO로 반환한다.
- 읽기 요청은 provider 호출과 원장 쓰기를 만들지 않는다.
- feature path는 Store Operator audience에만 연결된다.
- 공개 DTO unit, 권한 Service, Controller, OpenAPI, architecture와 영향 MySQL lifecycle test를 focused 실행한다.
- 전체 로컬 backend suite는 실행하지 않고 GitHub CI를 full-suite 정본으로 사용한다.
