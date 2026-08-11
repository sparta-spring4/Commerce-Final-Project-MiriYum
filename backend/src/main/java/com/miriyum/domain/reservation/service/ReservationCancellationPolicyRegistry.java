package com.miriyum.domain.reservation.service;

import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import java.util.Optional;

/**
 * 저장된 예약 취소 정책 버전을 현재 알려진 정책으로 해석한다.
 */
public class ReservationCancellationPolicyRegistry {

    private static final ReservationCancellationPolicyVersion VERSION_ONE =
            new ReservationCancellationPolicyVersion(1L);

    public Optional<ReservationCancellationPolicyVersion> findByStoredVersion(
            Long storedVersion) {
        if (storedVersion == null || storedVersion != VERSION_ONE.value()) {
            return Optional.empty();
        }
        return Optional.of(VERSION_ONE);
    }
}
