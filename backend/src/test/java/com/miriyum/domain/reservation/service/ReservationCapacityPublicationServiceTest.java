package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;

import com.miriyum.domain.reservation.dto.request.CapacityBucketRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.core.service.StoreScheduleAuthority;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class ReservationCapacityPublicationServiceTest {

    private static final long OPERATOR_ID = 11L;
    private static final long STORE_ID = 7L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 10);
    private static final String KEY = "550e8400-e29b-41d4-a716-446655440000";

    @Mock
    private StoreService storeService;

    @Mock
    private StoreServiceIntervalValidationService intervalValidationService;

    @Mock
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    private ObjectMapper objectMapper;
    private ReservationCapacityPublicationService service;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        service = new ReservationCapacityPublicationService(
                storeService,
                intervalValidationService,
                new ReservationCapacityPolicy(),
                capacityBucketRepository,
                reservationRepository,
                idempotencyExecutor,
                objectMapper
        );
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    objectMapper.valueToTree(result.data())
            );
        });
    }

    @Test
    void publishesAWholeNewVersionWithExistingConfirmedOccupancy() {
        // given
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        StoreServiceIntervalRequest firstInterval = new StoreServiceIntervalRequest(
                STORE_ID,
                Instant.parse("2026-08-10T09:00:00Z"),
                Instant.parse("2026-08-10T09:30:00Z")
        );
        StoreServiceIntervalRequest secondInterval = new StoreServiceIntervalRequest(
                STORE_ID,
                Instant.parse("2026-08-10T09:30:00Z"),
                Instant.parse("2026-08-10T10:00:00Z")
        );
        given(intervalValidationService.validateServiceIntervals(
                List.of(firstInterval, secondInterval)
        )).willReturn(List.of(
                StoreServiceIntervalResult.of(firstInterval, true),
                StoreServiceIntervalResult.of(secondInterval, true)
        ));
        given(capacityBucketRepository.findLatestPolicyBucketsForUpdate(
                STORE_ID,
                SERVICE_DATE
        )).willReturn(List.of());
        given(reservationRepository.findConfirmedForCapacityPublication(
                STORE_ID,
                SERVICE_DATE
        )).willReturn(List.of(confirmedReservation()));
        given(capacityBucketRepository.saveAllAndFlush(any())).willAnswer(invocation -> {
            List<ReservationCapacityBucket> buckets = invocation.getArgument(0);
            ReflectionTestUtils.setField(buckets.get(0), "id", 101L);
            ReflectionTestUtils.setField(buckets.get(1), "id", 102L);
            return buckets;
        });
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 30, 19, 0, 10, 2),
                bucket(18, 0, 18, 30, 4, 0)
        ));

        // when
        ReservationCapacityCommandResult result = service.replaceCapacities(
                OPERATOR_ID,
                STORE_ID,
                SERVICE_DATE,
                IdempotencyKey.parse(KEY),
                request
        );

        // then
        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data().policyVersion()).isEqualTo(1L);
        assertThat(result.data().buckets())
                .extracting(bucket -> bucket.capacityBucketId())
                .containsExactly("101", "102");
        assertThat(result.data().buckets()).allSatisfy(bucket -> {
            assertThat(bucket.occupiedPeople()).isEqualTo(5);
            assertThat(bucket.occupiedTeams()).isEqualTo(1);
        });
        assertThat(result.data().buckets().getFirst().availablePeople()).isZero();
        assertThat(result.data().buckets().getFirst().availableTeams()).isZero();
        assertThat(result.data().buckets().getLast().availablePeople()).isEqualTo(5);
        assertThat(result.data().buckets().getLast().availableTeams()).isEqualTo(1);
        then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
        then(capacityBucketRepository).should().findLatestPolicyBucketsForUpdate(
                STORE_ID,
                SERVICE_DATE
        );
    }

    @Test
    void publishesSeparatedBucketsAsOnePolicyVersion() {
        // given
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        List<StoreServiceIntervalRequest> intervals = List.of(
                interval("2026-08-10T02:00:00Z", "2026-08-10T02:30:00Z"),
                interval("2026-08-10T02:30:00Z", "2026-08-10T03:00:00Z"),
                interval("2026-08-10T09:00:00Z", "2026-08-10T09:30:00Z"),
                interval("2026-08-10T09:30:00Z", "2026-08-10T10:00:00Z")
        );
        given(intervalValidationService.validateServiceIntervals(intervals))
                .willReturn(intervals.stream()
                        .map(request -> StoreServiceIntervalResult.of(request, true))
                        .toList());
        given(capacityBucketRepository.findLatestPolicyBucketsForUpdate(
                STORE_ID,
                SERVICE_DATE
        )).willReturn(List.of(existingBucket(2L)));
        given(reservationRepository.findConfirmedForCapacityPublication(
                STORE_ID,
                SERVICE_DATE
        )).willReturn(List.of());
        givenSuccessfulSave();
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 30, 19, 0, 8, 2),
                bucket(11, 0, 11, 30, 8, 2),
                bucket(18, 0, 18, 30, 8, 2),
                bucket(11, 30, 12, 0, 8, 2)
        ));

        // when
        ReservationCapacityCommandResult result = service.replaceCapacities(
                OPERATOR_ID,
                STORE_ID,
                SERVICE_DATE,
                IdempotencyKey.parse(KEY),
                request
        );

        // then
        assertThat(result.data().policyVersion()).isEqualTo(3L);
        assertThat(result.data().buckets())
                .extracting(bucket -> bucket.startTime())
                .containsExactly(
                        LocalTime.of(11, 0),
                        LocalTime.of(11, 30),
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30)
                );
    }

    @Test
    void publishesSeparatedBucketsWithoutRequiringFullWindowCoverage() {
        // given
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        List<StoreServiceIntervalRequest> intervals = List.of(
                interval("2026-08-10T09:00:00Z", "2026-08-10T09:30:00Z"),
                interval("2026-08-10T09:45:00Z", "2026-08-10T10:00:00Z")
        );
        given(intervalValidationService.validateServiceIntervals(intervals))
                .willReturn(intervals.stream()
                        .map(request -> StoreServiceIntervalResult.of(request, true))
                        .toList());
        given(capacityBucketRepository.findLatestPolicyBucketsForUpdate(
                STORE_ID,
                SERVICE_DATE
        )).willReturn(List.of(existingBucket(2L)));
        given(reservationRepository.findConfirmedForCapacityPublication(
                STORE_ID,
                SERVICE_DATE
        )).willReturn(List.of());
        givenSuccessfulSave();
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 18, 30, 8, 2),
                bucket(18, 45, 19, 0, 8, 2)
        ));

        // when
        ReservationCapacityCommandResult result = service.replaceCapacities(
                OPERATOR_ID,
                STORE_ID,
                SERVICE_DATE,
                IdempotencyKey.parse(KEY),
                request
        );

        // then
        assertThat(result.data().policyVersion()).isEqualTo(3L);
        assertThat(result.data().buckets())
                .extracting(bucket -> bucket.startTime())
                .containsExactly(LocalTime.of(18, 0), LocalTime.of(18, 45));
    }

    private static StoreServiceIntervalRequest interval(String startAt, String endAt) {
        return new StoreServiceIntervalRequest(
                STORE_ID,
                Instant.parse(startAt),
                Instant.parse(endAt)
        );
    }

    private void givenSuccessfulSave() {
        given(capacityBucketRepository.saveAllAndFlush(any())).willAnswer(invocation -> {
            List<ReservationCapacityBucket> buckets = invocation.getArgument(0);
            for (int index = 0; index < buckets.size(); index++) {
                ReflectionTestUtils.setField(buckets.get(index), "id", 201L + index);
            }
            return buckets;
        });
    }

    private static ReservationCapacityBucket existingBucket(long version) {
        return ReservationCapacityBucket.create(
                STORE_ID,
                SERVICE_DATE,
                LocalTime.of(10, 0),
                LocalTime.of(10, 30),
                8,
                2,
                0,
                0,
                1,
                4,
                true,
                version
        );
    }

    private static CapacityBucketRequest bucket(
            int startHour,
            int startMinute,
            int endHour,
            int endMinute,
            int maxPeople,
            int maxTeams
    ) {
        return new CapacityBucketRequest(
                LocalTime.of(startHour, startMinute),
                LocalTime.of(endHour, endMinute),
                maxPeople,
                maxTeams,
                1,
                Math.min(maxPeople, 4),
                true
        );
    }

    private static Reservation confirmedReservation() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                STORE_ID,
                1L,
                15,
                30,
                0
        );
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "initial publication");
        ReservationTimeSnapshot timeSnapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(SERVICE_DATE, LocalTime.of(18, 15)),
                ZoneId.of("Asia/Seoul"),
                null
        );
        Reservation reservation = Reservation.confirm(
                31L,
                STORE_ID,
                "MiriYum",
                timeSnapshot,
                PartyComposition.of(3, 1, 1),
                ReservationContactSnapshot.contactable("notification-target:31"),
                1L,
                Instant.parse("2026-08-01T00:00:00Z")
        );
        ReflectionTestUtils.setField(reservation, "id", 301L);
        return reservation;
    }
}
