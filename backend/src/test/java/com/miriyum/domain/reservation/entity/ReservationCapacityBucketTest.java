package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCapacityBucketTest {

    @Test
    @DisplayName("수용량 한도와 현재 점유 및 정책 버전을 보존한다")
    void preservesCapacityPolicyAndOccupancy() {
        // when
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L,
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                20,
                5,
                8,
                2,
                1,
                6,
                true,
                3L
        );

        // then
        assertThat(bucket.getStoreId()).isEqualTo(22L);
        assertThat(bucket.getMaxPeople()).isEqualTo(20);
        assertThat(bucket.getMaxTeams()).isEqualTo(5);
        assertThat(bucket.getOccupiedPeople()).isEqualTo(8);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(2);
        assertThat(bucket.getMinPartySize()).isEqualTo(1);
        assertThat(bucket.getMaxPartySize()).isEqualTo(6);
        assertThat(bucket.isInfantsAllowed()).isTrue();
        assertThat(bucket.getPolicyVersion()).isEqualTo(3L);
    }

    @Test
    @DisplayName("기존 점유가 새 최대값보다 커도 이력 보존을 위해 허용한다")
    void allowsOccupancyAboveNewLimits() {
        // when
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L,
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                4,
                1,
                8,
                2,
                1,
                4,
                true,
                4L
        );

        // then
        assertThat(bucket.getOccupiedPeople()).isGreaterThan(bucket.getMaxPeople());
        assertThat(bucket.getOccupiedTeams()).isGreaterThan(bucket.getMaxTeams());
    }

    @Test
    @DisplayName("남은 인원과 팀 수가 정확히 맞으면 예약 가능하다")
    void acceptsPartyAtRemainingPeopleAndTeamBoundary() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                6,
                3,
                2,
                2,
                1,
                4,
                true
        );

        // when
        boolean available = bucket.canAccept(4, false);

        // then
        assertThat(available).isTrue();
    }

    @Test
    @DisplayName("잠긴 버킷은 인원과 팀 한 건을 함께 점유한다")
    void occupiesPeopleAndOneTeamTogether() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                10,
                3,
                4,
                1,
                1,
                6,
                true
        );

        // when
        bucket.occupy(3);

        // then
        assertThat(bucket.getOccupiedPeople()).isEqualTo(7);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(2);
    }

    @Test
    @DisplayName("인원 또는 팀 수용량이 부족하면 버킷 점유를 전혀 변경하지 않고 RESERVATION_003을 반환한다")
    void keepsOccupancyUnchangedWhenPeopleOrTeamCapacityIsInsufficient() {
        // given
        ReservationCapacityBucket peopleFull = capacityBucket(
                5,
                3,
                4,
                1,
                1,
                5,
                true
        );
        ReservationCapacityBucket teamsFull = capacityBucket(
                10,
                2,
                2,
                2,
                1,
                6,
                true
        );

        // when & then
        assertThatThrownBy(() -> peopleFull.occupy(2))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.INSUFFICIENT_CAPACITY);
        assertThat(peopleFull.getOccupiedPeople()).isEqualTo(4);
        assertThat(peopleFull.getOccupiedTeams()).isEqualTo(1);

        assertThatThrownBy(() -> teamsFull.occupy(2))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(ReservationErrorCode.INSUFFICIENT_CAPACITY);
        assertThat(teamsFull.getOccupiedPeople()).isEqualTo(2);
        assertThat(teamsFull.getOccupiedTeams()).isEqualTo(2);
    }

    @Test
    @DisplayName("잠긴 버킷은 인원과 팀 한 건을 함께 정확히 복구한다")
    void restoresPeopleAndExactlyOneTeamTogether() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                10,
                3,
                5,
                2,
                1,
                6,
                true
        );

        // when
        bucket.restore(3, 1);

        // then
        assertThat(bucket.getOccupiedPeople()).isEqualTo(2);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
    }

    @Test
    @DisplayName("인원 복구가 점유 인원을 초과하면 두 점유량 모두 유지한다")
    void rejectsPeopleUnderflowWithoutPartialMutation() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                10,
                3,
                2,
                1,
                1,
                6,
                true
        );

        // when & then
        assertThatThrownBy(() -> bucket.restore(3, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bucket.getOccupiedPeople()).isEqualTo(2);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
    }

    @Test
    @DisplayName("양수가 아닌 인원 또는 한 건이 아닌 팀 복구를 거부하고 점유량을 유지한다")
    void rejectsInvalidRestoreArgumentsWithoutMutation() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                10,
                3,
                5,
                2,
                1,
                6,
                true
        );

        // when & then
        assertThatIllegalArgumentException().isThrownBy(() -> bucket.restore(0, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> bucket.restore(-1, 1));
        assertThatIllegalArgumentException().isThrownBy(() -> bucket.restore(1, 0));
        assertThatIllegalArgumentException().isThrownBy(() -> bucket.restore(1, 2));
        assertThat(bucket.getOccupiedPeople()).isEqualTo(5);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(2);
    }

    @Test
    @DisplayName("팀 점유가 없으면 복구를 거부하고 인원 점유도 유지한다")
    void rejectsTeamUnderflowWithoutPartialMutation() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                10,
                3,
                5,
                0,
                1,
                6,
                true
        );

        // when & then
        assertThatThrownBy(() -> bucket.restore(3, 1))
                .isInstanceOf(IllegalStateException.class);
        assertThat(bucket.getOccupiedPeople()).isEqualTo(5);
        assertThat(bucket.getOccupiedTeams()).isZero();
    }

    @Test
    @DisplayName("인원 한도가 부족하면 팀 수가 남아도 예약할 수 없다")
    void rejectsPartyWhenPeopleCapacityIsInsufficient() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                6,
                5,
                3,
                1,
                1,
                6,
                true
        );

        // when
        boolean available = bucket.canAccept(4, false);

        // then
        assertThat(available).isFalse();
    }

    @Test
    @DisplayName("팀 한도가 찼으면 인원 한도가 남아도 예약할 수 없다")
    void rejectsPartyWhenTeamCapacityIsInsufficient() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                10,
                2,
                2,
                2,
                1,
                6,
                true
        );

        // when
        boolean available = bucket.canAccept(2, false);

        // then
        assertThat(available).isFalse();
    }

    @Test
    @DisplayName("영유아를 허용하지 않는 버킷은 영유아 동반 요청을 거부한다")
    void rejectsInfantPartyWhenInfantsAreNotAllowed() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                10,
                4,
                0,
                0,
                1,
                6,
                false
        );

        // when
        boolean available = bucket.canAccept(3, true);

        // then
        assertThat(available).isFalse();
    }

    @Test
    @DisplayName("일행 인원이 버킷의 최소·최대 범위를 벗어나면 예약할 수 없다")
    void rejectsPartyOutsideBucketPartyRange() {
        // given
        ReservationCapacityBucket bucket = capacityBucket(
                10,
                4,
                0,
                0,
                2,
                4,
                true
        );

        // when & then
        assertThat(bucket.canAccept(1, false)).isFalse();
        assertThat(bucket.canAccept(5, false)).isFalse();
    }

    @Test
    @DisplayName("음수 수용량이나 점유량을 거부한다")
    void rejectsNegativeCapacityOrOccupancy() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        -1,
                        5,
                        0,
                        0,
                        1,
                        4,
                        true,
                        3L
                )
        );
    }

    @Test
    @DisplayName("최대 일행 인원이 최대 수용 인원을 넘으면 거부한다")
    void rejectsPartyMaximumAbovePeopleMaximum() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        4,
                        5,
                        0,
                        0,
                        1,
                        5,
                        true,
                        3L
                )
        );
    }

    @Test
    @DisplayName("최대 일행 인원이 최소 일행 인원보다 작으면 거부한다")
    void rejectsPartyMaximumBelowPartyMinimum() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        5,
                        4,
                        true,
                        3L
                )
        );
    }

    @Test
    @DisplayName("양수가 아닌 매장 ID와 정책 버전을 거부한다")
    void rejectsNonPositiveIdentityOrPolicyVersion() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        0L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        1,
                        4,
                        true,
                        3L
                )
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        1,
                        4,
                        true,
                        0L
                )
        );
    }

    @Test
    @DisplayName("필수 날짜 스냅샷이 없으면 생성할 수 없다")
    void rejectsMissingServiceDate() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        null,
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        20,
                        5,
                        0,
                        0,
                        1,
                        4,
                        true,
                        3L
                )
        );
    }

    @Test
    @DisplayName("종료 시각이 시작 시각보다 늦지 않으면 수용량 버킷 생성을 거부한다")
    void rejectsNonIncreasingServiceTime() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                capacityBucket(LocalTime.of(18, 0), LocalTime.of(18, 0))
        );
        assertThatIllegalArgumentException().isThrownBy(() ->
                capacityBucket(LocalTime.of(18, 0), LocalTime.of(17, 30))
        );
    }

    private static ReservationCapacityBucket capacityBucket(
            LocalTime startTime,
            LocalTime endTime
    ) {
        return ReservationCapacityBucket.create(
                22L,
                LocalDate.of(2026, 8, 1),
                startTime,
                endTime,
                20,
                5,
                0,
                0,
                1,
                4,
                true,
                3L
        );
    }

    @Test
    @DisplayName("최대 인원 0은 양수 불변식 위반으로 거부한다")
    void rejectsZeroMaxPeople() {
        // when & then
        assertThatIllegalArgumentException().isThrownBy(() ->
                ReservationCapacityBucket.create(
                        22L,
                        LocalDate.of(2026, 8, 1),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        0,
                        0,
                        0,
                        0,
                        1,
                        1,
                        true,
                        3L
                )
        ).withMessage("maxPeople must be positive");
    }

    @Test
    @DisplayName("최대 팀 수 0은 신규 예약 차단 상태로 보존한다")
    void allowsZeroMaxTeams() {
        // when
        ReservationCapacityBucket bucket = capacityBucket(
                1,
                0,
                0,
                0,
                1,
                1,
                true
        );

        // then
        assertThat(bucket.getMaxTeams()).isZero();
        assertThat(bucket.canAccept(1, false)).isFalse();
    }

    private static ReservationCapacityBucket capacityBucket(
            int maxPeople,
            int maxTeams,
            int occupiedPeople,
            int occupiedTeams,
            int minPartySize,
            int maxPartySize,
            boolean infantsAllowed
    ) {
        return ReservationCapacityBucket.create(
                22L,
                LocalDate.of(2026, 8, 1),
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                maxPeople,
                maxTeams,
                occupiedPeople,
                occupiedTeams,
                minPartySize,
                maxPartySize,
                infantsAllowed,
                3L
        );
    }
}
