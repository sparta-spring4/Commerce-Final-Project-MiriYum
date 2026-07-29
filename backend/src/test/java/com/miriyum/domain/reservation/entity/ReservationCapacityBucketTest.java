package com.miriyum.domain.reservation.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.time.LocalDate;
import java.time.LocalTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCapacityBucketTest {

    @Test
    @DisplayName("수용량 한도와 현재 점유 및 정책 버전을 보존한다")
    void preservesCapacityPolicyAndOccupancy() {
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

        assertThat(bucket.getOccupiedPeople()).isGreaterThan(bucket.getMaxPeople());
        assertThat(bucket.getOccupiedTeams()).isGreaterThan(bucket.getMaxTeams());
    }

    @Test
    @DisplayName("음수 수용량이나 점유량을 거부한다")
    void rejectsNegativeCapacityOrOccupancy() {
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
}
