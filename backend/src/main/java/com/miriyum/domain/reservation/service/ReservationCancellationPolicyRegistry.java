package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import java.util.Optional;

/**
 * 저장된 예약 취소 정책 버전을 현재 알려진 정책으로 해석한다.
 */
public class ReservationCancellationPolicyRegistry {

    private static final ReservationCancellationPolicyVersion VERSION_ONE =
            new ReservationCancellationPolicyVersion(1L);
    private static final ReservationCancellationPolicyVersion VERSION_TWO =
            new ReservationCancellationPolicyVersion(2L);

    public Optional<ReservationCancellationPolicyVersion> findByStoredVersion(
            Long storedVersion) {
        if (storedVersion == null) {
            return Optional.empty();
        }
        if (storedVersion == VERSION_ONE.value()) {
            return Optional.of(VERSION_ONE);
        }
        if (storedVersion == VERSION_TWO.value()) {
            return Optional.of(VERSION_TWO);
        }
        return Optional.empty();
    }
}
