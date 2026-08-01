package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 2);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);
    private static final LocalTime END_TIME = LocalTime.of(19, 0);

    @Mock
    private ReservationCapacityBucketRepository capacityBucketRepository;

    private ReservationService reservationService;

    @BeforeEach
    void setUp() {
        reservationService = new ReservationService(capacityBucketRepository);
    }

    @Test
    @DisplayName("요청 구간을 연속으로 덮는 모든 버킷에 여유가 있으면 예약 가능하다")
    void getAvailabilityReturnsAvailableWhenEveryContiguousBucketCanAccept() {
        // given
        ReservationAvailabilityCondition condition = condition(4, false);
        when(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                SERVICE_DATE,
                START_TIME,
                END_TIME
        )).thenReturn(List.of(
                bucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 6, 2),
                bucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 4, 1)
        ));

        // when
        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        // then
        assertThat(result).isEqualTo(new ReservationAvailabilityResult(
                22L,
                ReservationAvailabilityStatus.AVAILABLE
        ));
    }

    @Test
    @DisplayName("필요한 구간 사이에 버킷 공백이 있으면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenCoverageHasGap() {
        // given
        ReservationAvailabilityCondition condition = condition(2, false);
        when(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                SERVICE_DATE,
                START_TIME,
                END_TIME
        )).thenReturn(List.of(
                bucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                bucket(22L, LocalTime.of(18, 45), LocalTime.of(19, 0), 0, 0)
        ));

        // when
        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        // then
        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("필요 구간에서 버킷이 겹치면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenCoverageOverlaps() {
        // given
        ReservationAvailabilityCondition condition = condition(2, false);
        when(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                SERVICE_DATE,
                START_TIME,
                END_TIME
        )).thenReturn(List.of(
                bucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 45), 0, 0),
                bucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0)
        ));

        // when
        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        // then
        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("필요 구간에 서로 다른 정책 버전이 섞이면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenPolicyVersionsAreMixed() {
        // given
        ReservationAvailabilityCondition condition = condition(2, false);
        when(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                SERVICE_DATE,
                START_TIME,
                END_TIME
        )).thenReturn(List.of(
                bucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0, 3L),
                bucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0, 4L)
        ));

        // when
        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        // then
        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("겹치는 버킷 중 하나라도 수용량이 부족하면 전체 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenAnyBucketIsInsufficient() {
        // given
        ReservationAvailabilityCondition condition = condition(4, false);
        when(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                SERVICE_DATE,
                START_TIME,
                END_TIME
        )).thenReturn(List.of(
                bucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 6, 2),
                bucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 8, 1)
        ));

        // when
        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        // then
        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("일괄 판정은 저장소를 한 번 조회하고 입력 매장 순서대로 대응한다")
    void getAvailabilitiesUsesOneQueryAndPreservesInputOrder() {
        // given
        ReservationAvailabilityCondition condition = condition(2, false);
        List<Long> storeIds = List.of(30L, 10L, 20L);
        when(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                storeIds,
                SERVICE_DATE,
                START_TIME,
                END_TIME
        )).thenReturn(List.of(
                bucket(20L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0),
                bucket(30L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                bucket(20L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                bucket(30L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0)
        ));

        // when
        List<ReservationAvailabilityResult> results =
                reservationService.getAvailabilities(storeIds, condition);

        // then
        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        30L,
                        ReservationAvailabilityStatus.AVAILABLE
                ),
                new ReservationAvailabilityResult(
                        10L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                ),
                new ReservationAvailabilityResult(
                        20L,
                        ReservationAvailabilityStatus.AVAILABLE
                )
        );
        verify(capacityBucketRepository, times(1))
                .findLatestPolicyBucketsOverlapping(
                        storeIds,
                        SERVICE_DATE,
                        START_TIME,
                        END_TIME
                );
    }

    @Test
    @DisplayName("빈 매장 후보는 저장소를 조회하지 않고 빈 결과를 반환한다")
    void getAvailabilitiesReturnsEmptyWithoutRepositoryQuery() {
        // when
        List<ReservationAvailabilityResult> results =
                reservationService.getAvailabilities(List.of(), condition(2, false));

        // then
        assertThat(results).isEmpty();
        verify(capacityBucketRepository, never())
                .findLatestPolicyBucketsOverlapping(
                        List.of(),
                        SERVICE_DATE,
                        START_TIME,
                        END_TIME
                );
    }

    private static ReservationAvailabilityCondition condition(
            int partySize,
            boolean includesInfants
    ) {
        return new ReservationAvailabilityCondition(
                SERVICE_DATE,
                START_TIME,
                END_TIME,
                partySize,
                includesInfants
        );
    }

    private static ReservationCapacityBucket bucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            int occupiedPeople,
            int occupiedTeams
    ) {
        return bucket(
                storeId,
                startTime,
                endTime,
                occupiedPeople,
                occupiedTeams,
                3L
        );
    }

    private static ReservationCapacityBucket bucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            int occupiedPeople,
            int occupiedTeams,
            long policyVersion
    ) {
        return ReservationCapacityBucket.create(
                storeId,
                SERVICE_DATE,
                startTime,
                endTime,
                10,
                4,
                occupiedPeople,
                occupiedTeams,
                1,
                6,
                true,
                policyVersion
        );
    }
}
