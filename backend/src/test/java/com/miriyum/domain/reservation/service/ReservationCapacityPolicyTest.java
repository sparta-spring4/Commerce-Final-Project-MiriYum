package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.reservation.dto.request.CapacityBucketRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class ReservationCapacityPolicyTest {

    private final ReservationCapacityPolicy capacityPolicy =
            new ReservationCapacityPolicy();

    @Test
    @DisplayName("겹치지 않는 전체 수용량 버킷을 시작 시각 순서로 정규화한다")
    void validatesAndSortsNonOverlappingBuckets() {
        // given
        CapacityBucketRequest later = bucket(
                LocalTime.of(18, 30),
                LocalTime.of(19, 0),
                10,
                4,
                1,
                6
        );
        CapacityBucketRequest earlier = bucket(
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                12,
                5,
                1,
                6
        );

        // when
        List<CapacityBucketRequest> normalized = capacityPolicy.validateAndSort(
                new ReservationCapacitiesRequest(List.of(later, earlier))
        );

        // then
        assertThat(normalized).containsExactly(earlier, later);
    }

    @Test
    @DisplayName("게시 버킷의 시간 구간이 겹치면 수용량 설정 충돌로 거부한다")
    void rejectsOverlappingBuckets() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(
                List.of(
                        bucket(
                                LocalTime.of(18, 0),
                                LocalTime.of(18, 45),
                                10,
                                4,
                                1,
                                6
                        ),
                        bucket(
                                LocalTime.of(18, 30),
                                LocalTime.of(19, 0),
                                10,
                                4,
                                1,
                                6
                        )
                )
        );

        // when & then
        assertCapacityConflict(() -> capacityPolicy.validateAndSort(request));
    }

    @Test
    @DisplayName("종료 시각이 시작 시각보다 늦지 않으면 게시를 거부한다")
    void rejectsNonIncreasingBucketTime() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(
                List.of(bucket(
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 0),
                        10,
                        4,
                        1,
                        6
                ))
        );

        // when & then
        assertCapacityConflict(() -> capacityPolicy.validateAndSort(request));
    }

    @Test
    @DisplayName("최대 일행 인원이 최소 일행 인원보다 작으면 게시를 거부한다")
    void rejectsReversedPartyRange() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(
                List.of(bucket(
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        10,
                        4,
                        6,
                        5
                ))
        );

        // when & then
        assertCapacityConflict(() -> capacityPolicy.validateAndSort(request));
    }

    @Test
    @DisplayName("최대 일행 인원이 최대 수용 인원보다 크면 게시를 거부한다")
    void rejectsPartyMaximumAbovePeopleMaximum() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(
                List.of(bucket(
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        4,
                        4,
                        1,
                        5
                ))
        );

        // when & then
        assertCapacityConflict(() -> capacityPolicy.validateAndSort(request));
    }

    @Test
    @DisplayName("최대 인원 0은 수용량 설정 충돌로 거부한다")
    void rejectsZeroMaxPeople() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(
                List.of(bucket(
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        0,
                        4,
                        1,
                        1
                ))
        );

        // when & then
        assertCapacityConflict(() -> capacityPolicy.validateAndSort(request));
    }

    @Test
    @DisplayName("최대 팀 수 0은 신규 예약 차단 설정으로 허용한다")
    void allowsZeroMaxTeams() {
        // given
        CapacityBucketRequest bucket = bucket(
                LocalTime.of(18, 0),
                LocalTime.of(18, 30),
                1,
                0,
                1,
                1
        );

        // when
        List<CapacityBucketRequest> normalized = capacityPolicy.validateAndSort(
                new ReservationCapacitiesRequest(List.of(bucket))
        );

        // then
        assertThat(normalized).containsExactly(bucket);
    }

    private static CapacityBucketRequest bucket(
            LocalTime startTime,
            LocalTime endTime,
            int maxPeople,
            int maxTeams,
            int minPartySize,
            int maxPartySize
    ) {
        return new CapacityBucketRequest(
                startTime,
                endTime,
                maxPeople,
                maxTeams,
                minPartySize,
                maxPartySize,
                true
        );
    }

    private static void assertCapacityConflict(Runnable command) {
        assertThatThrownBy(command::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode
                                        .CAPACITY_CONFIGURATION_CONFLICT));
    }
}
