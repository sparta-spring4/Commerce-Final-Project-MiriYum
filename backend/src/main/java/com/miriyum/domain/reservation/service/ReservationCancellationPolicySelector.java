package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;

/**
 * 신규 예약에 저장할 알려진 취소 정책 버전을 선택한다.
 */
public class ReservationCancellationPolicySelector {

    private static final long VERSION_ONE = 1L;
    private static final long VERSION_TWO = 2L;

    private final ReservationCancellationPolicyVersion directReservationVersion;
    private final ReservationCancellationPolicyVersion depositReservationVersion;

    public ReservationCancellationPolicySelector(
            ReservationCancellationPolicyRegistry registry) {
        ReservationCancellationPolicyRegistry validatedRegistry = requireNonNull(
                registry,
                "registry"
        );
        this.directReservationVersion = validatedRegistry.findByStoredVersion(VERSION_ONE)
                .orElseThrow(() -> new IllegalStateException(
                        "reservation cancellation policy version 1 is not registered"
                ));
        this.depositReservationVersion = validatedRegistry.findByStoredVersion(VERSION_TWO)
                .orElseThrow(() -> new IllegalStateException(
                        "reservation cancellation policy version 2 is not registered"
                ));
    }

    public ReservationCancellationPolicyVersion select() {
        return directReservationVersion;
    }

    public ReservationCancellationPolicyVersion selectDeposit() {
        return depositReservationVersion;
    }

    private static <T> T requireNonNull(T value, String fieldName) {
        if (value == null) {
            throw new IllegalArgumentException(fieldName + " must not be null");
        }
        return value;
    }
}
