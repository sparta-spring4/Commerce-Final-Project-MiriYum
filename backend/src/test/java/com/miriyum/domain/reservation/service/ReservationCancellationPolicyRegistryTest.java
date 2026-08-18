package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import org.junit.jupiter.api.Test;

class ReservationCancellationPolicyRegistryTest {

    private final ReservationCancellationPolicyRegistry registry =
            new ReservationCancellationPolicyRegistry();

    @Test
    void recognizesStoredVersionsOneAndTwo() {
        assertThat(registry.findByStoredVersion(1L))
                .contains(new ReservationCancellationPolicyVersion(1L));
        assertThat(registry.findByStoredVersion(2L))
                .contains(new ReservationCancellationPolicyVersion(2L));
    }

    @Test
    void doesNotFallbackForNullInvalidOrUnknownStoredVersions() {
        assertThat(registry.findByStoredVersion(null)).isEmpty();
        assertThat(registry.findByStoredVersion(0L)).isEmpty();
        assertThat(registry.findByStoredVersion(-1L)).isEmpty();
        assertThat(registry.findByStoredVersion(3L)).isEmpty();
    }
}
