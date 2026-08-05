package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReservationCancellationPolicySelectorTest {

    @Test
    void selectsKnownVersionOneDeterministically() {
        ReservationCancellationPolicySelector selector =
                new ReservationCancellationPolicySelector(
                        new ReservationCancellationPolicyRegistry());

        assertThat(selector.select()).isEqualTo(new ReservationCancellationPolicyVersion(1L));
        assertThat(selector.select()).isEqualTo(new ReservationCancellationPolicyVersion(1L));
    }

    @Test
    void failsFastWhenTheRegistryDoesNotProvideVersionOne() {
        assertThatIllegalStateException().isThrownBy(() ->
                new ReservationCancellationPolicySelector(new MissingVersionOneRegistry()));
    }

    private static class MissingVersionOneRegistry extends ReservationCancellationPolicyRegistry {

        @Override
        public Optional<ReservationCancellationPolicyVersion> findByStoredVersion(
                Long storedVersion) {
            return Optional.empty();
        }
    }
}
