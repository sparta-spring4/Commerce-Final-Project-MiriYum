package com.miriyum.domain.reservation.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;

/**
 * 예약 확정 시점의 알림 대상과 연락 가능 여부를 보존한다.
 *
 * <p>알림 대상 참조는 연락처 원문이 아닌 공급자 중립의 불투명 문자열이다. 실제 참조값은
 * 예약 생성 유스케이스가 승인된 계정·알림 계약으로부터 전달해야 한다.</p>
 */
@Embeddable
public class ReservationContactSnapshot {

    private static final int MAX_TARGET_REFERENCE_LENGTH = 512;

    @Column(name = "notification_target_reference", nullable = false, length = 512)
    private String notificationTargetReference;

    @Column(name = "contact_available_at_confirmation", nullable = false)
    private boolean contactAvailableAtConfirmation;

    protected ReservationContactSnapshot() {
    }

    private ReservationContactSnapshot(String notificationTargetReference) {
        this.notificationTargetReference = requireTargetReference(notificationTargetReference);
        this.contactAvailableAtConfirmation = true;
    }

    /**
     * 확인된 연락 채널을 가진 예약자의 거래 스냅샷을 만든다.
     *
     * @param notificationTargetReference 연락처 원문이 아닌 불투명 알림 대상 참조
     * @return 확정 시점에 연락 가능한 스냅샷
     * @throws IllegalArgumentException 참조가 비어 있거나 512자를 초과하는 경우
     */
    public static ReservationContactSnapshot contactable(String notificationTargetReference) {
        return new ReservationContactSnapshot(notificationTargetReference);
    }

    private static String requireTargetReference(String value) {
        if (value == null || value.isBlank() || value.length() > MAX_TARGET_REFERENCE_LENGTH) {
            throw new IllegalArgumentException(
                    "notificationTargetReference must contain between 1 and 512 characters"
            );
        }
        return value;
    }

    public String getNotificationTargetReference() {
        return notificationTargetReference;
    }

    public boolean isContactAvailableAtConfirmation() {
        return contactAvailableAtConfirmation;
    }
}
