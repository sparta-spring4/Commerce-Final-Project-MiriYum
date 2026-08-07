package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ReservationCancellationPolicyVersionTest {

    @Test
    void preservesPositiveBigintValues() {
        ReservationCancellationPolicyVersion first =
                new ReservationCancellationPolicyVersion(1L);
        ReservationCancellationPolicyVersion maximum =
                new ReservationCancellationPolicyVersion(Long.MAX_VALUE);

        assertThat(first.value()).isEqualTo(1L);
        assertThat(maximum.value()).isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void rejectsZeroAndNegativeValues() {
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReservationCancellationPolicyVersion(0L));
        assertThatIllegalArgumentException().isThrownBy(
                () -> new ReservationCancellationPolicyVersion(-1L));
    }
}
