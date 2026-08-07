package com.miriyum.domain.consumer.dto.response;

/**
 * Reservation이 예약 생성 시 사용할 일반 사용자 연락처 결과다.
 *
 * @param notificationTargetReference 실제 전화번호가 아닌 불투명 참조값
 * @param contactAvailable 연락처를 사용할 수 있는지 여부
 */
public record ReservationContactResult(
        String notificationTargetReference,
        boolean contactAvailable
) {
}
