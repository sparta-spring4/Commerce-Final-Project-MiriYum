package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCapacityAllocationTest {

    @Test
    @DisplayName("예약별 실제 점유 인원과 팀 1건 및 정책 버전을 보존한다")
    void preservesAllocationSnapshot() {
        // when
        ReservationCapacityAllocation allocation =
                ReservationCapacityAllocation.allocate(101L, 202L, 4, 3L);

        // then
        assertThat(allocation.getReservationId()).isEqualTo(101L);
        assertThat(allocation.getCapacityBucketId()).isEqualTo(202L);
        assertThat(allocation.getOccupiedPeople()).isEqualTo(4);
        assertThat(allocation.getOccupiedTeams()).isEqualTo(1);
        assertThat(allocation.getCapacityPolicyVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("양수가 아닌 예약과 버킷 ID를 거부한다")
    void rejectsNonPositiveReferences() {
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReservationCapacityAllocation.allocate(0L, 202L, 4, 3L));
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReservationCapacityAllocation.allocate(101L, 0L, 4, 3L));
    }

    @Test
    @DisplayName("점유 인원은 1명 이상이어야 한다")
    void rejectsEmptyOccupiedPeople() {
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReservationCapacityAllocation.allocate(101L, 202L, 0, 3L));
    }

    @Test
    @DisplayName("수용량 정책 버전은 양수여야 한다")
    void rejectsNonPositivePolicyVersion() {
        // when & then
        assertThatIllegalArgumentException()
                .isThrownBy(() -> ReservationCapacityAllocation.allocate(101L, 202L, 4, 0L));
    }
}
