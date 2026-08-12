package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;

import com.miriyum.domain.reservation.dto.request.CapacityBucketRequest;
import com.miriyum.domain.reservation.dto.request.ReservationCapacitiesRequest;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.store.service.StoreScheduleAuthority;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import com.miriyum.global.exception.ServiceException;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.List;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.InOrder;
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
    private ReservationHoldRepository reservationHoldRepository;

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
                reservationHoldRepository,
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

    @Test
    @DisplayName("확정 예약과 ACTIVE 선점을 겹치는 새 버킷마다 각각 한 번 이월한다")
    void carriesConfirmedReservationAndActiveHoldOccupancyIntoEveryOverlap() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 18, 30, 20, 5),
                bucket(18, 30, 19, 0, 20, 5)
        ));
        givenReadyPublication(
                List.of(confirmedReservation()),
                List.of(hold(ReservationHoldStatus.ACTIVE, PartyComposition.of(2, 1, 0),
                        LocalTime.of(18, 15), 30, 0)),
                request
        );
        givenSuccessfulSave();

        // when
        ReservationCapacityCommandResult result = replace(request);

        // then
        assertThat(result.data().buckets()).allSatisfy(bucket -> {
            assertThat(bucket.occupiedPeople()).isEqualTo(8);
            assertThat(bucket.occupiedTeams()).isEqualTo(2);
        });
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationHoldStatus.class,
            names = {"RECONCILIATION_REQUIRED", "CONFIRMED"}
    )
    @DisplayName("점유 보호 상태 선점은 새 정책 버킷에 이월한다")
    void carriesEveryProtectedHoldStatus(ReservationHoldStatus status) {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 18, 30, 10, 3)
        ));
        givenReadyPublication(
                List.of(),
                List.of(hold(status, PartyComposition.of(2, 1, 0),
                        LocalTime.of(18, 15), 30, 0)),
                request
        );
        givenSuccessfulSave();

        // when
        ReservationCapacityCommandResult result = replace(request);

        // then
        assertThat(result.data().buckets().getFirst().occupiedPeople()).isEqualTo(3);
        assertThat(result.data().buckets().getFirst().occupiedTeams()).isEqualTo(1);
    }

    @ParameterizedTest
    @EnumSource(value = ReservationHoldStatus.class, names = {"RELEASED", "EXPIRED"})
    @DisplayName("종결 선점이 저장소 결과에 섞이면 실패 폐쇄하고 게시하지 않는다")
    void rejectsTerminalHoldReturnedByRepository(ReservationHoldStatus status) {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 18, 30, 10, 3)
        ));
        givenReadyPublication(
                List.of(),
                List.of(hold(status, PartyComposition.of(1, 0, 0),
                        LocalTime.of(18, 15), 30, 0)),
                request
        );

        // when & then
        assertCapacityConflict(() -> replace(request));
        then(capacityBucketRepository).should(never()).saveAllAndFlush(any());
    }

    @ParameterizedTest(name = "오염 필드: {0}")
    @ValueSource(strings = {
            "store",
            "date",
            "time",
            "party",
            "negativePartyComponent",
            "serviceEndBeforeStart",
            "serviceEndAfterOccupancyEnd",
            "invalidZone",
            "startOffset",
            "serviceEndOffset",
            "occupancyEndOffset",
            "localServiceDate",
            "timeSecondPrecision",
            "timeNanoPrecision"
    })
    @DisplayName("선점 거래 스냅샷이 오염되면 실패 폐쇄하고 게시하지 않는다")
    void rejectsCorruptedHoldSnapshot(String corruptedField) {
        // given
        ReservationHold hold = hold(
                ReservationHoldStatus.ACTIVE,
                PartyComposition.of(1, 0, 0),
                LocalTime.of(18, 15),
                30,
                0
        );
        corrupt(hold, corruptedField);
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 18, 30, 10, 3)
        ));
        givenReadyPublication(List.of(), List.of(hold), request);

        // when & then
        assertCapacityConflict(() -> replace(request));
        then(capacityBucketRepository).should(never()).saveAllAndFlush(any());
    }

    @Test
    @DisplayName("Store 권한부터 확정 예약, 보호 선점, 현재 버킷 순서로 잠근다")
    void locksStoreThenConfirmedReservationsThenProtectedHoldsThenCurrentBuckets() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 18, 30, 10, 3)
        ));
        givenReadyPublication(
                List.of(confirmedReservation()),
                List.of(hold(ReservationHoldStatus.ACTIVE, PartyComposition.of(1, 0, 0),
                        LocalTime.of(18, 15), 30, 0)),
                request
        );
        givenSuccessfulSave();

        // when
        replace(request);

        // then
        InOrder order = inOrder(
                storeService,
                reservationRepository,
                reservationHoldRepository,
                capacityBucketRepository
        );
        order.verify(storeService).requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID);
        order.verify(reservationRepository).findConfirmedForCapacityPublication(
                STORE_ID,
                SERVICE_DATE
        );
        order.verify(reservationHoldRepository)
                .findProtectedByStoreIdAndServiceDateForUpdateOrderByIdAsc(
                        STORE_ID,
                        SERVICE_DATE
                );
        order.verify(capacityBucketRepository).findLatestPolicyBucketsForUpdate(
                STORE_ID,
                SERVICE_DATE
        );
    }

    @Test
    @DisplayName("분할된 새 버킷마다 선점의 전체 인원과 한 팀을 이월한다")
    void carriesWholeHoldPartyAcrossSplitBuckets() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 18, 30, 10, 3),
                bucket(18, 30, 19, 0, 10, 3),
                bucket(19, 0, 19, 30, 10, 3),
                bucket(19, 30, 20, 0, 10, 3)
        ));
        givenReadyPublication(
                List.of(),
                List.of(hold(ReservationHoldStatus.ACTIVE, PartyComposition.of(3, 1, 0),
                        LocalTime.of(18, 15), 45, 15)),
                request
        );
        givenSuccessfulSave();

        // when
        ReservationCapacityCommandResult result = replace(request);

        // then
        assertThat(result.data().buckets())
                .extracting(bucket -> bucket.occupiedPeople())
                .containsExactly(4, 4, 4, 0);
        assertThat(result.data().buckets())
                .extracting(bucket -> bucket.occupiedTeams())
                .containsExactly(1, 1, 1, 0);
    }

    @Test
    @DisplayName("여러 구간을 가로지른 선점도 병합된 새 버킷에는 한 번만 이월한다")
    void carriesHoldRootOnlyOnceIntoMergedBucket() {
        // given
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 20, 0, 10, 3)
        ));
        givenReadyPublication(
                List.of(),
                List.of(hold(ReservationHoldStatus.ACTIVE, PartyComposition.of(3, 1, 0),
                        LocalTime.of(18, 15), 45, 15)),
                request
        );
        givenSuccessfulSave();

        // when
        ReservationCapacityCommandResult result = replace(request);

        // then
        assertThat(result.data().buckets().getFirst().occupiedPeople()).isEqualTo(4);
        assertThat(result.data().buckets().getFirst().occupiedTeams()).isEqualTo(1);
    }

    @Test
    @DisplayName("선점 점유 합산이 정수 범위를 넘으면 실패 폐쇄한다")
    void rejectsHoldOccupancyOverflow() {
        // given
        ReservationHold maximum = hold(
                ReservationHoldStatus.ACTIVE,
                PartyComposition.of(1, 0, 0),
                LocalTime.of(18, 15),
                30,
                0
        );
        ReflectionTestUtils.setField(maximum.getParty(), "adultCount", Integer.MAX_VALUE);
        ReservationHold oneMore = hold(
                ReservationHoldStatus.ACTIVE,
                PartyComposition.of(1, 0, 0),
                LocalTime.of(18, 15),
                30,
                0
        );
        ReservationCapacitiesRequest request = new ReservationCapacitiesRequest(List.of(
                bucket(18, 0, 18, 30, 10, 3)
        ));
        givenReadyPublication(List.of(), List.of(maximum, oneMore), request);

        // when & then
        assertCapacityConflict(() -> replace(request));
        then(capacityBucketRepository).should(never()).saveAllAndFlush(any());
    }

    private static StoreServiceIntervalRequest interval(String startAt, String endAt) {
        return new StoreServiceIntervalRequest(
                STORE_ID,
                Instant.parse(startAt),
                Instant.parse(endAt)
        );
    }

    private void givenReadyPublication(
            List<Reservation> reservations,
            List<ReservationHold> holds,
            ReservationCapacitiesRequest request
    ) {
        given(storeService.requireSchedulePublicationAuthority(OPERATOR_ID, STORE_ID))
                .willReturn(new StoreScheduleAuthority(STORE_ID, "Asia/Seoul"));
        List<StoreServiceIntervalRequest> intervals = request.buckets().stream()
                .map(bucket -> new StoreServiceIntervalRequest(
                        STORE_ID,
                        LocalDateTime.of(SERVICE_DATE, bucket.startTime())
                                .atZone(ZoneId.of("Asia/Seoul"))
                                .toInstant(),
                        LocalDateTime.of(SERVICE_DATE, bucket.endTime())
                                .atZone(ZoneId.of("Asia/Seoul"))
                                .toInstant()
                ))
                .toList();
        given(intervalValidationService.validateServiceIntervals(intervals))
                .willReturn(intervals.stream()
                        .map(value -> StoreServiceIntervalResult.of(value, true))
                        .toList());
        given(reservationRepository.findConfirmedForCapacityPublication(
                STORE_ID,
                SERVICE_DATE
        )).willReturn(reservations);
        given(reservationHoldRepository
                .findProtectedByStoreIdAndServiceDateForUpdateOrderByIdAsc(
                        STORE_ID,
                        SERVICE_DATE
                )).willReturn(holds);
        given(capacityBucketRepository.findLatestPolicyBucketsForUpdate(
                STORE_ID,
                SERVICE_DATE
        )).willReturn(List.of());
    }

    private ReservationCapacityCommandResult replace(ReservationCapacitiesRequest request) {
        return service.replaceCapacities(
                OPERATOR_ID,
                STORE_ID,
                SERVICE_DATE,
                IdempotencyKey.parse(KEY),
                request
        );
    }

    private static void assertCapacityConflict(
            org.assertj.core.api.ThrowableAssert.ThrowingCallable call
    ) {
        assertThatThrownBy(call)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT
                        ));
    }

    private static void corrupt(ReservationHold hold, String corruptedField) {
        switch (corruptedField) {
            case "store" -> ReflectionTestUtils.setField(hold, "storeId", STORE_ID + 1);
            case "date" -> ReflectionTestUtils.setField(
                    hold.getTimeSnapshot(),
                    "serviceDate",
                    SERVICE_DATE.plusDays(1)
            );
            case "time" -> ReflectionTestUtils.setField(
                    hold.getTimeSnapshot(),
                    "startAt",
                    null
            );
            case "party" -> ReflectionTestUtils.setField(hold, "party", null);
            case "negativePartyComponent" -> {
                ReflectionTestUtils.setField(hold.getParty(), "adultCount", -1);
                ReflectionTestUtils.setField(hold.getParty(), "childCount", 2);
            }
            case "serviceEndBeforeStart" -> ReflectionTestUtils.setField(
                    hold.getTimeSnapshot(),
                    "serviceEndAt",
                    hold.getStartAt().minusSeconds(60)
            );
            case "serviceEndAfterOccupancyEnd" -> ReflectionTestUtils.setField(
                    hold.getTimeSnapshot(),
                    "serviceEndAt",
                    hold.getOccupancyEndAt().plusSeconds(60)
            );
            case "invalidZone" -> ReflectionTestUtils.setField(
                    hold.getTimeSnapshot(),
                    "timeZoneId",
                    "Invalid/Zone"
            );
            case "startOffset" -> ReflectionTestUtils.setField(
                    hold.getTimeSnapshot(),
                    "startOffsetSeconds",
                    hold.getTimeSnapshot().getStartOffsetSeconds() + 1
            );
            case "serviceEndOffset" -> ReflectionTestUtils.setField(
                    hold.getTimeSnapshot(),
                    "serviceEndOffsetSeconds",
                    hold.getTimeSnapshot().getServiceEndOffsetSeconds() + 1
            );
            case "occupancyEndOffset" -> ReflectionTestUtils.setField(
                    hold.getTimeSnapshot(),
                    "occupancyEndOffsetSeconds",
                    hold.getTimeSnapshot().getOccupancyEndOffsetSeconds() + 1
            );
            case "localServiceDate" -> {
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "startAt",
                        hold.getStartAt().plusSeconds(86_400)
                );
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "serviceEndAt",
                        hold.getServiceEndAt().plusSeconds(86_400)
                );
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "occupancyEndAt",
                        hold.getOccupancyEndAt().plusSeconds(86_400)
                );
            }
            case "timeSecondPrecision" -> {
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "startAt",
                        hold.getStartAt().plusSeconds(1)
                );
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "serviceEndAt",
                        hold.getServiceEndAt().plusSeconds(1)
                );
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "occupancyEndAt",
                        hold.getOccupancyEndAt().plusSeconds(1)
                );
            }
            case "timeNanoPrecision" -> {
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "startAt",
                        hold.getStartAt().plusNanos(1)
                );
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "serviceEndAt",
                        hold.getServiceEndAt().plusNanos(1)
                );
                ReflectionTestUtils.setField(
                        hold.getTimeSnapshot(),
                        "occupancyEndAt",
                        hold.getOccupancyEndAt().plusNanos(1)
                );
            }
            default -> throw new IllegalArgumentException("unknown corrupted field");
        }
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
                new ReservationCancellationPolicyVersion(1L),
                Instant.parse("2026-08-01T00:00:00Z")
        );
        ReflectionTestUtils.setField(reservation, "id", 301L);
        return reservation;
    }

    private static ReservationHold hold(
            ReservationHoldStatus status,
            PartyComposition party,
            LocalTime startTime,
            int serviceDurationMinutes,
            int turnoverDurationMinutes
    ) {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                STORE_ID,
                2L,
                15,
                serviceDurationMinutes,
                turnoverDurationMinutes
        );
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "hold policy");
        ReservationTimeSnapshot timeSnapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(SERVICE_DATE, startTime),
                ZoneId.of("Asia/Seoul"),
                null
        );
        ReservationHold hold = ReservationHold.active(
                41L,
                STORE_ID,
                "MiriYum",
                timeSnapshot,
                party,
                ReservationContactSnapshot.contactable("notification-target:41"),
                1L,
                new ReservationCancellationPolicyVersion(1L),
                "hold-command-" + status + "-" + startTime,
                Instant.parse("2026-08-01T00:00:00Z")
        );
        switch (status) {
            case ACTIVE -> {
            }
            case RECONCILIATION_REQUIRED -> hold.requireReconciliation();
            case CONFIRMED -> hold.confirm();
            case RELEASED -> hold.release();
            case EXPIRED -> hold.expire(hold.getExpiresAt());
        }
        return hold;
    }
}
