package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalStateException;

import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import java.util.Optional;
import org.junit.jupiter.api.Test;

class ReservationCancellationPolicySelectorTest {

    @Test
    void selectsVersionOneForDirectReservationsAndVersionTwoForDeposits() {
        ReservationCancellationPolicySelector selector =
                new ReservationCancellationPolicySelector(
                        new ReservationCancellationPolicyRegistry());

        assertThat(selector.select()).isEqualTo(new ReservationCancellationPolicyVersion(1L));
        assertThat(selector.select()).isEqualTo(new ReservationCancellationPolicyVersion(1L));
        assertThat(selector.selectDeposit())
                .isEqualTo(new ReservationCancellationPolicyVersion(2L));
        assertThat(selector.selectDeposit())
                .isEqualTo(new ReservationCancellationPolicyVersion(2L));
    }

    @Test
    void failsFastWhenTheRegistryDoesNotProvideVersionOne() {
        assertThatIllegalStateException().isThrownBy(() ->
                new ReservationCancellationPolicySelector(new MissingVersionOneRegistry()));
    }

    @Test
    void failsFastWhenTheRegistryDoesNotProvideVersionTwo() {
        assertThatIllegalStateException().isThrownBy(() ->
                new ReservationCancellationPolicySelector(new MissingVersionTwoRegistry()));
    }

    private static class MissingVersionOneRegistry extends ReservationCancellationPolicyRegistry {

        @Override
        public Optional<ReservationCancellationPolicyVersion> findByStoredVersion(
                Long storedVersion) {
            return Optional.empty();
        }
    }

    private static class MissingVersionTwoRegistry extends ReservationCancellationPolicyRegistry {

        @Override
        public Optional<ReservationCancellationPolicyVersion> findByStoredVersion(
                Long storedVersion) {
            return storedVersion != null && storedVersion == 1L
                    ? Optional.of(new ReservationCancellationPolicyVersion(1L))
                    : Optional.empty();
        }
    }
}
