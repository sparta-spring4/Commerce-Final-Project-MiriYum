package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.Test;

class ReservationHoldCapacityAllocationTest {

    @Test
    void snapshotsOneTeamAndPeopleForOneHoldBucket() {
        ReservationHoldCapacityAllocation allocation =
                ReservationHoldCapacityAllocation.allocate(11L, 22L, 4, 3L);

        assertThat(allocation.getReservationHoldId()).isEqualTo(11L);
        assertThat(allocation.getCapacityBucketId()).isEqualTo(22L);
        assertThat(allocation.getOccupiedPeople()).isEqualTo(4);
        assertThat(allocation.getOccupiedTeams()).isOne();
        assertThat(allocation.getCapacityPolicyVersion()).isEqualTo(3L);
    }

    @Test
    void rejectsNonPositiveIdentityQuantityAndPolicy() {
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldCapacityAllocation.allocate(0L, 22L, 4, 3L));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldCapacityAllocation.allocate(11L, 0L, 4, 3L));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldCapacityAllocation.allocate(11L, 22L, 0, 3L));
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationHoldCapacityAllocation.allocate(11L, 22L, 4, 0L));
    }
}
