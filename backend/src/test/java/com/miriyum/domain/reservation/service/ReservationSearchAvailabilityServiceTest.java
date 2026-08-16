package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.times;

import com.miriyum.domain.reservation.dto.request.ReservationSearchAvailabilityCondition;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ResolvedReservationTime;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import java.time.Instant;
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
class ReservationSearchAvailabilityServiceTest {

    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 17);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);

    @Mock
    private ReservationTimeResolutionService timeResolutionService;

    @Mock
    private ReservationCapacityBucketRepository capacityBucketRepository;

    private ReservationSearchAvailabilityService service;

    @BeforeEach
    void setUp() {
        service = new ReservationSearchAvailabilityService(
                timeResolutionService,
                capacityBucketRepository
        );
    }

    @Test
    @DisplayName("인원이 없으면 모든 점유 버킷이 함께 받을 수 있는 실제 인원이 있을 때 예약 가능하다")
    void returnsAvailableWhenAnUnspecifiedPartySizeCanFitEveryBucket() {
        List<Long> storeIds = List.of(7L);
        given(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).willReturn(List.of(resolved(7L, START_TIME, LocalTime.of(19, 0))));
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                storeIds,
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 0)
        )).willReturn(List.of(
                bucket(7L, LocalTime.of(18, 0), LocalTime.of(18, 30), 7, 0, 2, 5),
                bucket(7L, LocalTime.of(18, 30), LocalTime.of(19, 0), 8, 0, 1, 4)
        ));

        List<ReservationAvailabilityResult> results = service.getAvailabilities(
                storeIds,
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        START_TIME,
                        null,
                        null,
                        false
                )
        );

        assertThat(results).containsExactly(new ReservationAvailabilityResult(
                7L,
                ReservationAvailabilityStatus.AVAILABLE
        ));
    }

    @Test
    @DisplayName("시간이 없으면 최신 날짜 버킷의 시작 시각 중 예약 가능한 후보가 하나라도 있으면 예약 가능하다")
    void returnsAvailableWhenAnyBucketStartIsCurrentlyReservable() {
        List<Long> storeIds = List.of(7L);
        List<ReservationCapacityBucket> buckets = List.of(
                bucket(7L, LocalTime.of(17, 0), LocalTime.of(18, 0), 10, 10, 1, 4),
                bucket(7L, LocalTime.of(18, 0), LocalTime.of(18, 30), 10, 0, 1, 4),
                bucket(7L, LocalTime.of(18, 30), LocalTime.of(19, 0), 10, 0, 1, 4)
        );
        given(capacityBucketRepository.findLatestPolicyBuckets(
                storeIds,
                SERVICE_DATE
        )).willReturn(buckets);
        given(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, LocalTime.of(17, 0), null)
        )).willReturn(List.of(ReservationTimeResolutionResult.unavailable(7L)));
        given(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, LocalTime.of(18, 0), null)
        )).willReturn(List.of(resolved(7L, LocalTime.of(18, 0), LocalTime.of(19, 0))));

        List<ReservationAvailabilityResult> results = service.getAvailabilities(
                storeIds,
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        null,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(new ReservationAvailabilityResult(
                7L,
                ReservationAvailabilityStatus.AVAILABLE
        ));
        then(capacityBucketRepository).should(times(1))
                .findLatestPolicyBuckets(storeIds, SERVICE_DATE);
    }

    @Test
    @DisplayName("시간 해석 배치가 입력 매장과 정확히 대응하지 않으면 전체 결과를 실패 폐쇄한다")
    void failsClosedForMismatchedTimeResolutionBatch() {
        List<Long> storeIds = List.of(7L, 8L);
        given(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).willReturn(List.of(
                resolved(7L, START_TIME, LocalTime.of(19, 0)),
                ReservationTimeResolutionResult.unavailable(99L)
        ));
        List<ReservationAvailabilityResult> results = service.getAvailabilities(
                storeIds,
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        START_TIME,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        7L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                ),
                new ReservationAvailabilityResult(
                        8L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                )
        );
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("수용량 배치에 요청하지 않은 매장 버킷이 섞이면 전체 결과를 실패 폐쇄한다")
    void failsClosedForUnexpectedCapacityBucket() {
        List<Long> storeIds = List.of(7L);
        given(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).willReturn(List.of(resolved(7L, START_TIME, LocalTime.of(19, 0))));
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                storeIds,
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 0)
        )).willReturn(List.of(
                bucket(7L, LocalTime.of(18, 0), LocalTime.of(18, 30), 10, 0, 1, 4),
                bucket(7L, LocalTime.of(18, 30), LocalTime.of(19, 0), 10, 0, 1, 4),
                bucket(99L, LocalTime.of(18, 0), LocalTime.of(19, 0), 10, 0, 1, 4)
        ));

        List<ReservationAvailabilityResult> results = service.getAvailabilities(
                storeIds,
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        START_TIME,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(new ReservationAvailabilityResult(
                7L,
                ReservationAvailabilityStatus.UNAVAILABLE
        ));
    }

    @Test
    @DisplayName("RESOLVED 시간 payload 하나가 내부 계약과 다르면 전체 배치를 실패 폐쇄한다")
    void failsWholeBatchForMalformedResolvedTimePayload() {
        List<Long> storeIds = List.of(7L, 8L);
        given(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).willReturn(List.of(
                resolved(7L, START_TIME, LocalTime.of(19, 0)),
                resolved(8L, 99L, START_TIME, LocalTime.of(19, 0))
        ));
        List<ReservationAvailabilityResult> results = service.getAvailabilities(
                storeIds,
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        START_TIME,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        7L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                ),
                new ReservationAvailabilityResult(
                        8L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                )
        );
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("수용량 응답 한 매장에 정책 버전이 섞이면 전체 배치를 실패 폐쇄한다")
    void failsWholeBatchForMixedCapacityPolicyVersions() {
        List<Long> storeIds = List.of(7L, 8L);
        given(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).willReturn(List.of(
                resolved(7L, START_TIME, LocalTime.of(19, 0)),
                resolved(8L, START_TIME, LocalTime.of(18, 30))
        ));
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                storeIds,
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 0)
        )).willReturn(List.of(
                bucket(7L, LocalTime.of(18, 0), LocalTime.of(19, 0), 10, 0, 1, 4),
                bucket(8L, LocalTime.of(18, 0), LocalTime.of(18, 30), 10, 0, 1, 4),
                bucket(8L, LocalTime.of(18, 30), LocalTime.of(19, 0), 10, 0, 1, 4, 2L)
        ));

        List<ReservationAvailabilityResult> results = service.getAvailabilities(
                storeIds,
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        START_TIME,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        7L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                ),
                new ReservationAvailabilityResult(
                        8L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                )
        );
    }

    @Test
    @DisplayName("날짜 검색의 다음 후보 시간이 잘못되면 앞선 예약 가능 판정도 되돌린다")
    void dateOnlyFailsWholeBatchAfterEarlierAvailableCandidate() {
        List<Long> storeIds = List.of(7L, 8L);
        given(capacityBucketRepository.findLatestPolicyBuckets(
                storeIds,
                SERVICE_DATE
        )).willReturn(List.of(
                bucket(7L, LocalTime.of(17, 0), LocalTime.of(18, 0), 10, 0, 1, 4),
                bucket(8L, LocalTime.of(18, 0), LocalTime.of(19, 0), 10, 0, 1, 4)
        ));
        given(timeResolutionService.resolveReservationTimes(
                List.of(7L),
                new ReservationTimeRequest(SERVICE_DATE, LocalTime.of(17, 0), null)
        )).willReturn(List.of(resolved(
                7L,
                LocalTime.of(17, 0),
                LocalTime.of(18, 0)
        )));
        given(timeResolutionService.resolveReservationTimes(
                List.of(8L),
                new ReservationTimeRequest(SERVICE_DATE, LocalTime.of(18, 0), null)
        )).willReturn(List.of(resolved(
                8L,
                99L,
                LocalTime.of(18, 0),
                LocalTime.of(19, 0)
        )));

        List<ReservationAvailabilityResult> results = service.getAvailabilities(
                storeIds,
                new ReservationSearchAvailabilityCondition(
                        SERVICE_DATE,
                        null,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        7L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                ),
                new ReservationAvailabilityResult(
                        8L,
                        ReservationAvailabilityStatus.UNAVAILABLE
                )
        );
    }

    private static ReservationTimeResolutionResult resolved(
            long storeId,
            LocalTime startTime,
            LocalTime occupancyEndTime
    ) {
        return resolved(storeId, storeId, startTime, occupancyEndTime);
    }

    private static ReservationTimeResolutionResult resolved(
            long storeId,
            long policyStoreId,
            LocalTime startTime,
            LocalTime occupancyEndTime
    ) {
        Instant startAt = SERVICE_DATE.atTime(startTime)
                .toInstant(java.time.ZoneOffset.ofHours(9));
        Instant occupancyEndAt = SERVICE_DATE.atTime(occupancyEndTime)
                .toInstant(java.time.ZoneOffset.ofHours(9));
        return ReservationTimeResolutionResult.resolved(
                storeId,
                new ResolvedReservationTime(
                        SERVICE_DATE,
                        startAt,
                        occupancyEndAt,
                        occupancyEndAt,
                        "Asia/Seoul",
                        32_400,
                        32_400,
                        32_400,
                        30,
                        60,
                        0,
                        policyStoreId,
                        1L
                )
        );
    }

    private static ReservationCapacityBucket bucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            int maxPeople,
            int occupiedPeople,
            int minPartySize,
            int maxPartySize
    ) {
        return bucket(
                storeId,
                startTime,
                endTime,
                maxPeople,
                occupiedPeople,
                minPartySize,
                maxPartySize,
                1L
        );
    }

    private static ReservationCapacityBucket bucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            int maxPeople,
            int occupiedPeople,
            int minPartySize,
            int maxPartySize,
            long policyVersion
    ) {
        return ReservationCapacityBucket.create(
                storeId,
                SERVICE_DATE,
                startTime,
                endTime,
                maxPeople,
                5,
                occupiedPeople,
                0,
                minPartySize,
                maxPartySize,
                true,
                policyVersion
        );
    }
}
