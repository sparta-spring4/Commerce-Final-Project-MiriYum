package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;

import com.miriyum.domain.consumer.dto.contract.ReservationContactResult;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.dto.ReservationHoldContracts;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationHold;
import com.miriyum.domain.reservation.entity.ReservationHoldCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationHoldStatus;
import com.miriyum.domain.reservation.entity.ReservationHoldTransitionAudit;
import com.miriyum.domain.reservation.entity.ReservationHoldWarningTask;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldTransitionAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationHoldWarningTaskRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.service.StoreScheduleService;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.domain.store.dto.contract.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

@ExtendWith(MockitoExtension.class)
class ReservationHoldServiceTest {

    private static final long CONSUMER_ID = 11L;
    private static final long STORE_ID = 222L;
    private static final long HOLD_ID = 77L;
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 3);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);
    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final UUID AUDIT_UUID =
            UUID.fromString("123e4567-e89b-12d3-a456-426614174000");
    private static final String CREATION_COMMAND_ID = "hold-create-1";
    private static final String TRANSITION_OPERATION_ID = "hold-transition-1";

    @Mock
    private ReservationHoldRepository holdRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ReservationHoldCapacityAllocationRepository allocationRepository;

    @Mock
    private ReservationHoldTransitionAuditRepository auditRepository;

    @Mock
    private ReservationHoldWarningTaskRepository warningTaskRepository;

    @Mock
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Mock
    private ConsumerAccountService consumerAccountService;

    @Mock
    private StoreTransactionEligibilityService storeEligibilityService;

    @Mock
    private StoreScheduleService storeScheduleService;

    @Mock
    private StoreServiceIntervalValidationService intervalValidationService;

    @Mock
    private ReservationTimePolicyVersionRepository timePolicyRepository;

    private AtomicInteger auditIdGenerationCount;
    private ReservationHoldService service;

    @BeforeEach
    void setUp() {
        ReservationTimeResolutionService timeResolutionService =
                new ReservationTimeResolutionService(
                        storeScheduleService,
                        intervalValidationService,
                        timePolicyRepository,
                        Clock.fixed(NOW, ZoneOffset.UTC));
        ReservationCancellationPolicySelector cancellationPolicySelector =
                new ReservationCancellationPolicySelector(
                        new ReservationCancellationPolicyRegistry());
        auditIdGenerationCount = new AtomicInteger();
        service = new ReservationHoldService(
                holdRepository,
                reservationRepository,
                allocationRepository,
                auditRepository,
                warningTaskRepository,
                capacityBucketRepository,
                consumerAccountService,
                storeEligibilityService,
                timeResolutionService,
                cancellationPolicySelector,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> {
                    auditIdGenerationCount.incrementAndGet();
                    return AUDIT_UUID;
                });
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("sameMeaningReplayOffsets")
    @DisplayName("같은 생성 명령의 같은 의미 replay는 현재 선점을 반환하고 fresh 부작용을 만들지 않는다")
    void sameMeaningReplayReturnsCurrentHoldWithoutFreshSideEffects(
            String ignoredDescription,
            ZoneOffset requestedOffset
    ) {
        ReservationHold existing = existingHold(CREATION_COMMAND_ID);
        existing.requireReconciliation();
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID)).willReturn(Optional.of(existing));

        ReservationHoldContracts.Result result = service.create(
                command(STORE_ID, requestedOffset, CREATION_COMMAND_ID));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(result.status()).isEqualTo(ReservationHoldStatus.RECONCILIATION_REQUIRED);
        assertThat(result.consumerAccountId()).isEqualTo(CONSUMER_ID);
        assertThat(result.storeId()).isEqualTo(STORE_ID);
        assertThat(result.serviceDate()).isEqualTo(SERVICE_DATE);
        assertThat(result.startAt()).isEqualTo(Instant.parse("2026-08-03T09:00:00Z"));
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        then(holdRepository).should().findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID);
        then(holdRepository).shouldHaveNoMoreInteractions();
        verifyNoFreshInteractions();
        assertThat(auditIdGenerationCount).hasValue(0);
    }

    private static Stream<Arguments> sameMeaningReplayOffsets() {
        return Stream.of(
                Arguments.of("offset omitted", null),
                Arguments.of("stored resolved offset explicitly supplied", ZoneOffset.ofHours(9))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("differentMeaningReplayCommands")
    @DisplayName("같은 생성 명령의 사용자 입력 지문이 하나라도 다르면 COMMON_007이고 부작용이 없다")
    void differentMeaningReplayRejectsEveryFingerprintMismatchWithoutSideEffects(
            String ignoredDescription,
            ReservationHoldContracts.CreateCommand mismatchingCommand
    ) {
        ReservationHold existing = existingHold(CREATION_COMMAND_ID);
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(mismatchingCommand))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(holdRepository).should().findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID);
        then(holdRepository).shouldHaveNoMoreInteractions();
        verifyNoFreshInteractions();
        assertThat(auditIdGenerationCount).hasValue(0);
    }

    private static Stream<Arguments> differentMeaningReplayCommands() {
        return Stream.of(
                Arguments.of("store differs", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID + 1, SERVICE_DATE, START_TIME,
                        null, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("service date differs", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE.plusDays(1), START_TIME,
                        null, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("local start differs", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, LocalTime.of(18, 30),
                        null, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("explicit offset differs from resolved offset",
                        new ReservationHoldContracts.CreateCommand(
                                CONSUMER_ID, STORE_ID, SERVICE_DATE, START_TIME,
                                ZoneOffset.UTC, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("adult count differs", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, START_TIME,
                        null, 3, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("child count differs", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, START_TIME,
                        null, 2, 2, 0, CREATION_COMMAND_ID)),
                Arguments.of("infant count differs", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, START_TIME,
                        null, 2, 1, 1, CREATION_COMMAND_ID))
        );
    }

    @Test
    @DisplayName("공백을 제거한 같은 생성 명령 ID는 소비자 범위 replay를 조회하고 같은 결과를 반환한다")
    void normalizedCreationCommandIdUsesScopedReplayLookupWithoutFreshSideEffects() {
        ReservationHold existing = existingHold(CREATION_COMMAND_ID);
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID)).willReturn(Optional.of(existing));

        ReservationHoldContracts.Result result = service.create(
                command(STORE_ID, null, "  " + CREATION_COMMAND_ID + "  "));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(result.status()).isEqualTo(ReservationHoldStatus.ACTIVE);
        then(holdRepository).should().findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID);
        then(holdRepository).shouldHaveNoMoreInteractions();
        verifyNoFreshInteractions();
        assertThat(auditIdGenerationCount).hasValue(0);
    }

    @Test
    @DisplayName("동시 생성 replay miss는 Store 잠금 뒤 커밋된 Hold를 다시 확인해 현재 결과로 수렴한다")
    void concurrentCreateReplayMissConvergesAfterStoreLock() {
        ReservationHold existing = existingHold(CREATION_COMMAND_ID);
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID))
                .willReturn(Optional.empty(), Optional.of(existing));
        given(consumerAccountService.getReservationContact(CONSUMER_ID))
                .willReturn(new ReservationContactResult("opaque-contact-ref", true));
        given(storeEligibilityService.requireReservationTransactionEligibility(STORE_ID))
                .willReturn(new StoreReservationTransactionEligibility(STORE_ID, "미리윰 매장"));

        ReservationHoldContracts.Result result = service.create(
                command(STORE_ID, null, CREATION_COMMAND_ID));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(result.status()).isEqualTo(ReservationHoldStatus.ACTIVE);
        then(holdRepository).should(times(2))
                .findByConsumerAccountIdAndCreationCommandId(
                        CONSUMER_ID, CREATION_COMMAND_ID);
        then(storeScheduleService).shouldHaveNoInteractions();
        then(timePolicyRepository).shouldHaveNoInteractions();
        then(intervalValidationService).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(allocationRepository).shouldHaveNoInteractions();
        then(auditRepository).shouldHaveNoInteractions();
        then(warningTaskRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Store 잠금 뒤 발견한 생성 replay의 의미가 다르면 COMMON_007이다")
    void concurrentCreateReplayMissStillRejectsDifferentMeaningAfterStoreLock() {
        ReservationHold different = existingHold(CREATION_COMMAND_ID);
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID))
                .willReturn(Optional.empty(), Optional.of(different));
        given(consumerAccountService.getReservationContact(CONSUMER_ID))
                .willReturn(new ReservationContactResult("opaque-contact-ref", true));
        given(storeEligibilityService.requireReservationTransactionEligibility(STORE_ID + 1))
                .willReturn(new StoreReservationTransactionEligibility(
                        STORE_ID + 1, "다른 매장"));

        assertThatThrownBy(() -> service.create(
                command(STORE_ID + 1, null, CREATION_COMMAND_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(holdRepository).should(times(2))
                .findByConsumerAccountIdAndCreationCommandId(
                        CONSUMER_ID, CREATION_COMMAND_ID);
        then(storeScheduleService).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("fresh 생성은 고정 잠금 순서 뒤 실제 버킷과 동일 스냅샷의 선점 기록을 모두 저장한다")
    void freshCreationLocksOccupiesAndPersistsOneSnapshotInOrder() {
        List<ReservationCapacityBucket> buckets = List.of(
                bucket(301L, LocalTime.of(18, 0), LocalTime.of(19, 0)),
                bucket(302L, LocalTime.of(19, 0), LocalTime.of(19, 30))
        );
        stubFreshPath(buckets);
        stubHoldSaveWithGeneratedId();

        ReservationHoldContracts.Result result = service.create(
                command(STORE_ID, null, CREATION_COMMAND_ID));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(result.status()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertThat(result.createdAt()).isEqualTo(NOW);
        assertThat(result.expiresAt()).isEqualTo(NOW.plusSeconds(600));
        assertThat(buckets).allSatisfy(bucket -> {
            assertThat(bucket.getOccupiedPeople()).isEqualTo(3);
            assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
        });

        ArgumentCaptor<ReservationHold> holdCaptor =
                ArgumentCaptor.forClass(ReservationHold.class);
        then(holdRepository).should().saveAndFlush(holdCaptor.capture());
        ReservationHold savedHold = holdCaptor.getValue();
        assertThat(savedHold.getId()).isEqualTo(HOLD_ID);
        assertThat(savedHold.getStoreNameSnapshot()).isEqualTo("미리윰 매장");
        assertThat(savedHold.getParty().getAdultCount()).isEqualTo(2);
        assertThat(savedHold.getParty().getChildCount()).isEqualTo(1);
        assertThat(savedHold.getParty().getInfantCount()).isZero();
        assertThat(savedHold.getContactSnapshot().getNotificationTargetReference())
                .isEqualTo("opaque-contact-ref");
        assertThat(savedHold.getReservationTimePolicyVersion()).isEqualTo(5L);
        assertThat(savedHold.getCapacityPolicyVersion()).isEqualTo(7L);
        assertThat(savedHold.getCancellationPolicyVersion()).isEqualTo(1L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<ReservationHoldCapacityAllocation>> allocationsCaptor =
                ArgumentCaptor.forClass(List.class);
        then(allocationRepository).should().saveAll(allocationsCaptor.capture());
        assertThat(allocationsCaptor.getValue())
                .extracting(ReservationHoldCapacityAllocation::getCapacityBucketId)
                .containsExactly(301L, 302L);
        assertThat(allocationsCaptor.getValue()).allSatisfy(allocation -> {
            assertThat(allocation.getReservationHoldId()).isEqualTo(HOLD_ID);
            assertThat(allocation.getOccupiedPeople()).isEqualTo(3);
            assertThat(allocation.getOccupiedTeams()).isEqualTo(1);
            assertThat(allocation.getCapacityPolicyVersion()).isEqualTo(7L);
        });

        ArgumentCaptor<ReservationHoldTransitionAudit> auditCaptor =
                ArgumentCaptor.forClass(ReservationHoldTransitionAudit.class);
        then(auditRepository).should().save(auditCaptor.capture());
        ReservationHoldTransitionAudit audit = auditCaptor.getValue();
        assertThat(audit.getReservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(audit.getBeforeStatus()).isNull();
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertThat(audit.getActorType()).isEqualTo("SYSTEM");
        assertThat(audit.getActorId()).isNull();
        assertThat(audit.getRequestedAt()).isEqualTo(NOW);
        assertThat(audit.getOccurredAt()).isEqualTo(NOW);
        assertThat(audit.getReservationTimePolicyVersion()).isEqualTo(5L);
        assertThat(audit.getCapacityPolicyVersion()).isEqualTo(7L);
        assertThat(audit.getCommandId())
                .isEqualTo("reservation-hold-create:" + AUDIT_UUID)
                .isNotEqualTo(CREATION_COMMAND_ID);
        assertThat(auditIdGenerationCount).hasValue(1);

        ArgumentCaptor<ReservationHoldWarningTask> warningCaptor =
                ArgumentCaptor.forClass(ReservationHoldWarningTask.class);
        then(warningTaskRepository).should().save(warningCaptor.capture());
        assertThat(warningCaptor.getValue().getReservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(warningCaptor.getValue().getCreatedAt()).isEqualTo(NOW);
        assertThat(warningCaptor.getValue().getWarningDueAt())
                .isEqualTo(NOW.plusSeconds(480));

        InOrder order = inOrder(
                holdRepository,
                consumerAccountService,
                storeEligibilityService,
                storeScheduleService,
                timePolicyRepository,
                intervalValidationService,
                reservationRepository,
                capacityBucketRepository,
                allocationRepository,
                auditRepository,
                warningTaskRepository);
        order.verify(holdRepository).findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID);
        order.verify(consumerAccountService).getReservationContact(CONSUMER_ID);
        order.verify(storeEligibilityService).requireReservationTransactionEligibility(STORE_ID);
        order.verify(storeScheduleService).resolveReservationWindows(
                List.of(STORE_ID), SERVICE_DATE, START_TIME);
        order.verify(timePolicyRepository).findResolutionCandidatesByStoreIds(
                any(), any(), any(), any());
        order.verify(intervalValidationService).validateServiceIntervals(anyList());
        order.verify(reservationRepository).findConfirmedOverlappingForUpdate(
                CONSUMER_ID,
                STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
        order.verify(holdRepository).findProtectedOverlappingForUpdate(
                CONSUMER_ID,
                STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
        order.verify(capacityBucketRepository).findLatestPolicyBucketsOverlappingForUpdate(
                STORE_ID, SERVICE_DATE, START_TIME, LocalTime.of(19, 15));
        order.verify(holdRepository).saveAndFlush(any(ReservationHold.class));
        order.verify(allocationRepository).saveAll(anyList());
        order.verify(auditRepository).save(any(ReservationHoldTransitionAudit.class));
        order.verify(warningTaskRepository).save(any(ReservationHoldWarningTask.class));
    }

    @Test
    @DisplayName("확정 예약 겹침은 보호 Hold도 잠근 뒤 RESERVATION_004이고 버킷을 점유하지 않는다")
    void confirmedReservationOverlapRejectsAfterBothAggregateLocksWithoutOccupancy() {
        stubFreshBeforeAggregateLocks();
        given(reservationRepository.findConfirmedOverlappingForUpdate(
                CONSUMER_ID, STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(List.of(confirmedReservation()));
        given(holdRepository.findProtectedOverlappingForUpdate(
                CONSUMER_ID, STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(List.of());

        assertDuplicateWithoutCapacitySideEffects();

        InOrder order = inOrder(reservationRepository, holdRepository);
        order.verify(reservationRepository).findConfirmedOverlappingForUpdate(
                CONSUMER_ID, STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
        order.verify(holdRepository).findProtectedOverlappingForUpdate(
                CONSUMER_ID, STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
    }

    @Test
    @DisplayName("보호 Hold 겹침은 확정 예약 잠금 뒤 RESERVATION_004이고 버킷을 점유하지 않는다")
    void protectedHoldOverlapRejectsAfterConfirmedLockWithoutOccupancy() {
        stubFreshBeforeAggregateLocks();
        given(reservationRepository.findConfirmedOverlappingForUpdate(
                CONSUMER_ID, STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(List.of());
        given(holdRepository.findProtectedOverlappingForUpdate(
                CONSUMER_ID, STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(List.of(existingHold("other-command")));

        assertDuplicateWithoutCapacitySideEffects();
    }

    @Test
    @DisplayName("수용량 연속 coverage 검증 실패는 실제 버킷과 영속 상태를 변경하지 않는다")
    void capacityValidationFailureDoesNotOccupyOrPersist() {
        ReservationCapacityBucket first =
                bucket(301L, LocalTime.of(18, 0), LocalTime.of(18, 30));
        ReservationCapacityBucket afterGap =
                bucket(302L, LocalTime.of(19, 0), LocalTime.of(19, 30));
        List<ReservationCapacityBucket> buckets = List.of(first, afterGap);
        stubFreshPath(buckets);

        assertThatThrownBy(() -> service.create(
                command(STORE_ID, null, CREATION_COMMAND_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.INSUFFICIENT_CAPACITY));

        assertThat(buckets).allSatisfy(bucket -> {
            assertThat(bucket.getOccupiedPeople()).isZero();
            assertThat(bucket.getOccupiedTeams()).isZero();
        });
        then(holdRepository).should(never()).saveAndFlush(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(auditRepository).shouldHaveNoInteractions();
        then(warningTaskRepository).shouldHaveNoInteractions();
        assertThat(auditIdGenerationCount).hasValue(0);
    }

    @Test
    @DisplayName("allocation 저장 예외 뒤 audit와 warning 저장을 진행하지 않는다")
    void allocationPersistenceFailureStopsSubsequentPersistence() {
        List<ReservationCapacityBucket> buckets = List.of(
                bucket(301L, LocalTime.of(18, 0), LocalTime.of(19, 0)),
                bucket(302L, LocalTime.of(19, 0), LocalTime.of(19, 30))
        );
        stubFreshPath(buckets);
        stubHoldSaveWithGeneratedId();
        IllegalStateException persistenceFailure =
                new IllegalStateException("allocation persistence failed");
        given(allocationRepository.saveAll(anyList())).willThrow(persistenceFailure);

        assertThatThrownBy(() -> service.create(
                command(STORE_ID, null, CREATION_COMMAND_ID)))
                .isSameAs(persistenceFailure);

        assertThat(buckets).allSatisfy(bucket -> {
            assertThat(bucket.getOccupiedPeople()).isEqualTo(3);
            assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
        });
        then(holdRepository).should(times(1)).saveAndFlush(any(ReservationHold.class));
        then(auditRepository).shouldHaveNoInteractions();
        then(warningTaskRepository).shouldHaveNoInteractions();
        assertThat(auditIdGenerationCount).hasValue(0);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCommands")
    @DisplayName("구조적으로 잘못된 생성 명령은 replay 조회 전 거절한다")
    void invalidCommandRejectsBeforeReplay(String ignoredDescription, ReservationHoldContracts.CreateCommand command) {
        assertThatThrownBy(() -> service.create(command))
                .isInstanceOf(IllegalArgumentException.class);

        then(holdRepository).shouldHaveNoInteractions();
        verifyNoFreshInteractions();
    }

    @Test
    @DisplayName("같은 operation의 같은 Hold와 목표 상태 replay는 현재 결과만 반환한다")
    void transitionReplayReturnsCurrentHoldWithoutLockOrMutation() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold, ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        hold.confirm();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findById(HOLD_ID)).willReturn(Optional.of(hold));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                "  " + TRANSITION_OPERATION_ID + "  "));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        then(holdRepository).should().findById(HOLD_ID);
        then(holdRepository).should(never()).findByIdForUpdate(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitionReplayConflicts")
    @DisplayName("operation을 다른 Hold 또는 목표 상태에 재사용하면 COMMON_007이다")
    void transitionReplayRejectsDifferentMeaning(
            String ignoredDescription,
            long requestedHoldId,
            ReservationHoldStatus requestedTarget
    ) {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                requestedHoldId, requestedTarget, TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(holdRepository).shouldHaveNoInteractions();
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    private static Stream<Arguments> transitionReplayConflicts() {
        return Stream.of(
                Arguments.of("different hold", HOLD_ID + 1, ReservationHoldStatus.CONFIRMED),
                Arguments.of("different target", HOLD_ID, ReservationHoldStatus.RELEASED)
        );
    }

    @Test
    @DisplayName("생성 audit command ID와 충돌한 transition operation은 replay로 인정하지 않는다")
    void transitionOperationCollisionWithCreationAuditIsRejected() {
        ReservationHoldTransitionAudit creationAudit = ReservationHoldTransitionAudit.record(
                HOLD_ID,
                "SYSTEM",
                null,
                NOW,
                NOW,
                null,
                ReservationHoldStatus.ACTIVE,
                5L,
                7L,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(creationAudit));
        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.ACTIVE,
                TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(holdRepository).should(never()).findById(any());
        then(holdRepository).should(never()).findByIdForUpdate(any());
    }

    @Test
    @DisplayName("동시 transition replay miss는 Hold 잠금 뒤 커밋된 audit를 다시 확인해 현재 결과로 수렴한다")
    void concurrentTransitionReplayMissConvergesAfterHoldLock() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.confirm();
        ReservationHoldTransitionAudit committedAudit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.empty(), Optional.of(committedAudit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        then(auditRepository).should(times(2)).findByCommandId(TRANSITION_OPERATION_ID);
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("Hold 잠금 뒤 발견한 transition replay의 목표가 다르면 COMMON_007이다")
    void concurrentTransitionReplayMissStillRejectsDifferentMeaningAfterHoldLock() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationHoldTransitionAudit committedAudit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.empty(), Optional.of(committedAudit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RELEASED,
                TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(auditRepository).should(times(2)).findByCommandId(TRANSITION_OPERATION_ID);
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("Hold가 없으면 RESERVATION_001이고 Store 또는 버킷을 조회하지 않는다")
    void missingTransitionHoldFailsWithoutStoreOrCapacityLookup() {
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.empty());
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));

        then(storeEligibilityService).shouldHaveNoInteractions();
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("capacityRetainingTargets")
    @DisplayName("점유 유지 상태는 버킷을 읽지 않고 상태와 audit만 변경한다")
    void retainingTransitionChangesHoldAndAuditWithoutCapacityLookup(
            String ignoredDescription,
            ReservationHoldStatus before,
            ReservationHoldStatus target
    ) {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        if (before == ReservationHoldStatus.RECONCILIATION_REQUIRED) {
            hold.requireReconciliation();
        }
        stubFreshTransition(hold);

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID, target, TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(target);
        assertThat(hold.getStatus()).isEqualTo(target);
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        assertTransitionAuditSaved(before, target, NOW);
        then(storeEligibilityService).shouldHaveNoInteractions();
    }

    private static Stream<Arguments> capacityRetainingTargets() {
        return Stream.of(
                Arguments.of("active to confirmed",
                        ReservationHoldStatus.ACTIVE,
                        ReservationHoldStatus.CONFIRMED),
                Arguments.of("active to reconciliation required",
                        ReservationHoldStatus.ACTIVE,
                        ReservationHoldStatus.RECONCILIATION_REQUIRED),
                Arguments.of("reconciliation required to confirmed",
                        ReservationHoldStatus.RECONCILIATION_REQUIRED,
                        ReservationHoldStatus.CONFIRMED)
        );
    }

    @Test
    @DisplayName("정책 재게시 없는 해제는 같은 original/latest 버킷을 한 번만 복구한다")
    void releaseRestoresDeduplicatedOriginalAndLatestBucketsExactlyOnce() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationCapacityBucket first = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 0), 7L, 3, 1);
        ReservationCapacityBucket second = bucketWithOccupancy(
                302L, LocalTime.of(19, 0), LocalTime.of(19, 15), 7L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold,
                List.of(allocation(301L), allocation(302L)),
                List.of(301L, 302L),
                List.of(first, second));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        assertThat(first.getOccupiedPeople()).isZero();
        assertThat(first.getOccupiedTeams()).isZero();
        assertThat(second.getOccupiedPeople()).isZero();
        assertThat(second.getOccupiedTeams()).isZero();
        then(capacityBucketRepository).should().findAllByIdInForUpdate(List.of(301L, 302L));
        assertTransitionAuditSaved(
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RELEASED,
                NOW);
    }

    @Test
    @DisplayName("한 번 재게시된 split 정책은 original과 latest 합집합만 복구한다")
    void releaseAfterPolicySplitRestoresOriginalAndLatestUnion() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        ReservationCapacityBucket original = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        ReservationCapacityBucket latestFirst = bucketWithOccupancy(
                401L, LocalTime.of(18, 0), LocalTime.of(18, 30), 9L, 3, 1);
        ReservationCapacityBucket latestSecond = bucketWithOccupancy(
                402L, LocalTime.of(18, 30), LocalTime.of(19, 15), 9L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold,
                List.of(allocation(301L)),
                List.of(401L, 402L),
                List.of(original, latestFirst, latestSecond));

        service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID));

        assertThat(List.of(original, latestFirst, latestSecond))
                .allSatisfy(bucket -> {
                    assertThat(bucket.getOccupiedPeople()).isZero();
                    assertThat(bucket.getOccupiedTeams()).isZero();
                });
        then(capacityBucketRepository).should().findAllByIdInForUpdate(
                List.of(301L, 401L, 402L));
    }

    @Test
    @DisplayName("여러 번 재게시돼도 과거 중간 정책 버킷은 조회하거나 복구하지 않는다")
    void releaseAfterMultiplePublicationsExcludesIntermediateBuckets() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationCapacityBucket original = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        ReservationCapacityBucket intermediate = bucketWithOccupancy(
                401L, LocalTime.of(18, 0), LocalTime.of(19, 15), 8L, 3, 1);
        ReservationCapacityBucket latest = bucketWithOccupancy(
                501L, LocalTime.of(18, 0), LocalTime.of(19, 15), 9L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold,
                List.of(allocation(301L)),
                List.of(501L),
                List.of(original, latest));

        service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID));

        assertThat(original.getOccupiedPeople()).isZero();
        assertThat(latest.getOccupiedPeople()).isZero();
        assertThat(intermediate.getOccupiedPeople()).isEqualTo(3);
        assertThat(intermediate.getOccupiedTeams()).isEqualTo(1);
        then(capacityBucketRepository).should().findAllByIdInForUpdate(List.of(301L, 501L));
    }

    @Test
    @DisplayName("만료 정각에는 EXPIRED 전이와 용량 복구가 같은 중앙 시각으로 기록된다")
    void expiryAtBoundaryRestoresCapacityAndUsesOneClockInstant() {
        Instant createdAt = NOW.minusSeconds(600);
        ReservationHold hold = existingHold(CREATION_COMMAND_ID, createdAt);
        ReservationCapacityBucket bucket = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(hold, List.of(allocation(301L)), List.of(301L), List.of(bucket));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.EXPIRED, TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(bucket.getOccupiedPeople()).isZero();
        assertTransitionAuditSaved(
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.EXPIRED,
                NOW);
    }

    @Test
    @DisplayName("만료 직전 EXPIRED는 RESERVATION_005이고 allocation과 audit를 건드리지 않는다")
    void expiryBeforeBoundaryRejectsWithoutCapacityOrAudit() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID, NOW.minusSeconds(599));
        stubFreshTransition(hold);

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.EXPIRED, TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION));

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("invalidCapacityReleaseFixtures")
    @DisplayName("allocation 또는 latest union 불일치는 RESERVATION_008이고 audit를 저장하지 않는다")
    void invalidCapacityReleaseFailsClosedWithoutAudit(
            String ignoredDescription,
            List<ReservationHoldCapacityAllocation> allocations,
            List<Long> latestIds,
            List<ReservationCapacityBucket> lockedBuckets
    ) {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        stubFreshTransition(hold);
        stubCapacityRelease(hold, allocations, latestIds, lockedBuckets);

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT));

        then(auditRepository).should(never()).save(any());
    }

    private static Stream<Arguments> invalidCapacityReleaseFixtures() {
        return Stream.of(
                Arguments.of("allocation absent", List.of(), List.of(301L),
                        List.of(bucketWithOccupancy(
                                301L, LocalTime.of(18, 0), LocalTime.of(19, 15),
                                7L, 3, 1))),
                Arguments.of("partial original allocation omitted",
                        List.of(allocation(301L)),
                        List.of(401L),
                        List.of(
                                bucketWithOccupancy(301L, LocalTime.of(18, 0),
                                        LocalTime.of(18, 30), 7L, 3, 1),
                                bucketWithOccupancy(401L, LocalTime.of(18, 0),
                                        LocalTime.of(19, 15), 9L, 3, 1))),
                Arguments.of("latest coverage gap", List.of(allocation(301L)),
                        List.of(401L, 402L),
                        List.of(
                                bucketWithOccupancy(301L, LocalTime.of(18, 0),
                                        LocalTime.of(19, 15), 7L, 3, 1),
                                bucketWithOccupancy(401L, LocalTime.of(18, 0),
                                        LocalTime.of(18, 30), 9L, 3, 1),
                                bucketWithOccupancy(402L, LocalTime.of(19, 0),
                                        LocalTime.of(19, 15), 9L, 3, 1))),
                Arguments.of("latest coverage overlap", List.of(allocation(301L)),
                        List.of(401L, 402L),
                        List.of(
                                bucketWithOccupancy(301L, LocalTime.of(18, 0),
                                        LocalTime.of(19, 15), 7L, 3, 1),
                                bucketWithOccupancy(401L, LocalTime.of(18, 0),
                                        LocalTime.of(18, 45), 9L, 3, 1),
                                bucketWithOccupancy(402L, LocalTime.of(18, 30),
                                        LocalTime.of(19, 15), 9L, 3, 1))),
                Arguments.of("latest mixed version", List.of(allocation(301L)),
                        List.of(401L, 402L),
                        List.of(
                                bucketWithOccupancy(301L, LocalTime.of(18, 0),
                                        LocalTime.of(19, 15), 7L, 3, 1),
                                bucketWithOccupancy(401L, LocalTime.of(18, 0),
                                        LocalTime.of(18, 30), 9L, 3, 1),
                                bucketWithOccupancy(402L, LocalTime.of(18, 30),
                                        LocalTime.of(19, 15), 10L, 3, 1))),
                Arguments.of("union row missing", List.of(allocation(301L)),
                        List.of(401L),
                        List.of(bucketWithOccupancy(
                                301L, LocalTime.of(18, 0), LocalTime.of(19, 15),
                                7L, 3, 1)))
        );
    }

    private void stubFreshTransition(ReservationHold hold) {
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.empty());
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
    }

    private void stubCapacityRelease(
            ReservationHold hold,
            List<ReservationHoldCapacityAllocation> allocations,
            List<Long> latestBucketIds,
            List<ReservationCapacityBucket> lockedBuckets
    ) {
        given(allocationRepository.findAllByReservationHoldIdOrderByCapacityBucketIdAsc(
                HOLD_ID)).willReturn(allocations);
        if (allocations.isEmpty()) {
            return;
        }
        given(capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
                List.of(STORE_ID),
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 15))).willReturn(latestBucketIds);
        List<Long> unionIds = Stream.concat(
                        allocations.stream().map(
                                ReservationHoldCapacityAllocation::getCapacityBucketId),
                        latestBucketIds.stream())
                .distinct()
                .sorted()
                .toList();
        given(capacityBucketRepository.findAllByIdInForUpdate(unionIds))
                .willReturn(lockedBuckets);
    }

    private void assertTransitionAuditSaved(
            ReservationHoldStatus before,
            ReservationHoldStatus after,
            Instant occurredAt
    ) {
        ArgumentCaptor<ReservationHoldTransitionAudit> auditCaptor =
                ArgumentCaptor.forClass(ReservationHoldTransitionAudit.class);
        then(auditRepository).should().save(auditCaptor.capture());
        ReservationHoldTransitionAudit audit = auditCaptor.getValue();
        assertThat(audit.getReservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(audit.getBeforeStatus()).isEqualTo(before);
        assertThat(audit.getAfterStatus()).isEqualTo(after);
        assertThat(audit.getActorType()).isEqualTo("SYSTEM");
        assertThat(audit.getActorId()).isNull();
        assertThat(audit.getRequestedAt()).isEqualTo(NOW.minusSeconds(1));
        assertThat(audit.getOccurredAt()).isEqualTo(occurredAt);
        assertThat(audit.getReservationTimePolicyVersion()).isEqualTo(5L);
        assertThat(audit.getCapacityPolicyVersion()).isEqualTo(7L);
        assertThat(audit.getCommandId()).isEqualTo(TRANSITION_OPERATION_ID);
    }

    private static ReservationHoldContracts.TransitionCommand transitionCommand(
            long holdId,
            ReservationHoldStatus target,
            String operationId
    ) {
        return new ReservationHoldContracts.TransitionCommand(
                holdId,
                target,
                operationId,
                "SYSTEM",
                null,
                NOW.minusSeconds(1));
    }

    private static ReservationHoldTransitionAudit transitionAudit(
            ReservationHold hold,
            ReservationHoldStatus before,
            ReservationHoldStatus after,
            String operationId
    ) {
        return ReservationHoldTransitionAudit.record(
                HOLD_ID,
                "SYSTEM",
                null,
                NOW.minusSeconds(1),
                NOW,
                before,
                after,
                hold.getReservationTimePolicyVersion(),
                hold.getCapacityPolicyVersion(),
                operationId);
    }

    private static ReservationHoldCapacityAllocation allocation(long bucketId) {
        return ReservationHoldCapacityAllocation.allocate(HOLD_ID, bucketId, 3, 7L);
    }

    private static ReservationCapacityBucket bucketWithOccupancy(
            long id,
            LocalTime startTime,
            LocalTime endTime,
            long policyVersion,
            int occupiedPeople,
            int occupiedTeams
    ) {
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                STORE_ID,
                SERVICE_DATE,
                startTime,
                endTime,
                10,
                5,
                occupiedPeople,
                occupiedTeams,
                1,
                6,
                true,
                policyVersion);
        ReflectionTestUtils.setField(bucket, "id", id);
        return bucket;
    }

    private static Stream<Arguments> invalidCommands() {
        return Stream.of(
                Arguments.of("command missing", null),
                Arguments.of("consumer id not positive", new ReservationHoldContracts.CreateCommand(
                        0L, STORE_ID, SERVICE_DATE, START_TIME, null, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("store id not positive", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, 0L, SERVICE_DATE, START_TIME, null, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("service date missing", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, null, START_TIME, null, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("start time missing", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, null, null, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("start time not minute precision", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, LocalTime.of(18, 0, 1),
                        null, 2, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("negative party group", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, START_TIME, null, -1, 1, 0, CREATION_COMMAND_ID)),
                Arguments.of("empty party", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, START_TIME, null, 0, 0, 0, CREATION_COMMAND_ID)),
                Arguments.of("blank creation command", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, START_TIME, null, 2, 1, 0, "  ")),
                Arguments.of("creation command too long", new ReservationHoldContracts.CreateCommand(
                        CONSUMER_ID, STORE_ID, SERVICE_DATE, START_TIME, null, 2, 1, 0, "x".repeat(101)))
        );
    }

    private void assertDuplicateWithoutCapacitySideEffects() {
        assertThatThrownBy(() -> service.create(
                command(STORE_ID, null, CREATION_COMMAND_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.DUPLICATE_RESERVATION));

        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(holdRepository).should(never()).saveAndFlush(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(auditRepository).shouldHaveNoInteractions();
        then(warningTaskRepository).shouldHaveNoInteractions();
        assertThat(auditIdGenerationCount).hasValue(0);
    }

    private void stubFreshPath(List<ReservationCapacityBucket> buckets) {
        stubFreshBeforeAggregateLocks();
        given(reservationRepository.findConfirmedOverlappingForUpdate(
                CONSUMER_ID, STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(List.of());
        given(holdRepository.findProtectedOverlappingForUpdate(
                CONSUMER_ID, STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(List.of());
        given(capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                STORE_ID, SERVICE_DATE, START_TIME, LocalTime.of(19, 15)))
                .willReturn(buckets);
    }

    private void stubFreshBeforeAggregateLocks() {
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID)).willReturn(Optional.empty());
        given(consumerAccountService.getReservationContact(CONSUMER_ID))
                .willReturn(new ReservationContactResult("opaque-contact-ref", true));
        given(storeEligibilityService.requireReservationTransactionEligibility(STORE_ID))
                .willReturn(new StoreReservationTransactionEligibility(STORE_ID, "미리윰 매장"));
        stubResolvedCreationTime();
    }

    private void stubResolvedCreationTime() {
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                STORE_ID,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z"));
        given(storeScheduleService.resolveReservationWindows(
                List.of(STORE_ID), SERVICE_DATE, START_TIME))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        STORE_ID,
                        "Asia/Seoul",
                        requestedAt.minusHours(1),
                        requestedAt.plusHours(2))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                any(), any(), any(), any())).willReturn(List.of(activeTimePolicy()));
        given(intervalValidationService.validateServiceIntervals(List.of(interval)))
                .willReturn(List.of(StoreServiceIntervalResult.of(interval, true)));
    }

    private void stubHoldSaveWithGeneratedId() {
        given(holdRepository.saveAndFlush(any(ReservationHold.class)))
                .willAnswer(invocation -> {
                    ReservationHold saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", HOLD_ID);
                    return saved;
                });
    }

    private void verifyNoFreshInteractions() {
        then(consumerAccountService).shouldHaveNoInteractions();
        then(storeEligibilityService).shouldHaveNoInteractions();
        then(storeScheduleService).shouldHaveNoInteractions();
        then(timePolicyRepository).shouldHaveNoInteractions();
        then(intervalValidationService).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(allocationRepository).shouldHaveNoInteractions();
        then(auditRepository).shouldHaveNoInteractions();
        then(warningTaskRepository).shouldHaveNoInteractions();
    }

    private static ReservationHoldContracts.CreateCommand command(
            long storeId,
            ZoneOffset startOffset,
            String creationCommandId
    ) {
        return new ReservationHoldContracts.CreateCommand(
                CONSUMER_ID,
                storeId,
                SERVICE_DATE,
                START_TIME,
                startOffset,
                2,
                1,
                0,
                creationCommandId);
    }

    private static ReservationHold existingHold(String creationCommandId) {
        return existingHold(creationCommandId, NOW);
    }

    private static ReservationHold existingHold(
            String creationCommandId,
            Instant createdAt
    ) {
        ReservationHold hold = ReservationHold.active(
                CONSUMER_ID,
                STORE_ID,
                "미리윰 매장",
                timeSnapshot(),
                PartyComposition.of(2, 1, 0),
                ReservationContactSnapshot.contactable("stored-contact-ref"),
                7L,
                new ReservationCancellationPolicyVersion(1L),
                creationCommandId,
                createdAt);
        ReflectionTestUtils.setField(hold, "id", HOLD_ID);
        return hold;
    }

    private static Reservation confirmedReservation() {
        Reservation reservation = Reservation.confirm(
                CONSUMER_ID,
                STORE_ID,
                "미리윰 매장",
                timeSnapshot(),
                PartyComposition.of(2, 1, 0),
                ReservationContactSnapshot.contactable("reservation-contact-ref"),
                7L,
                new ReservationCancellationPolicyVersion(1L),
                NOW);
        ReflectionTestUtils.setField(reservation, "id", 88L);
        return reservation;
    }

    private static ReservationTimeSnapshot timeSnapshot() {
        return ReservationTimeSnapshot.calculate(
                activeTimePolicy(),
                LocalDateTime.of(SERVICE_DATE, START_TIME),
                java.time.ZoneId.of("Asia/Seoul"),
                null);
    }

    private static ReservationTimePolicyVersion activeTimePolicy() {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                STORE_ID, 5L, 30, 60, 15);
        policy.activate(NOW.minusSeconds(1), "test active policy");
        return policy;
    }

    private static ReservationCapacityBucket bucket(
            long id,
            LocalTime startTime,
            LocalTime endTime
    ) {
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                STORE_ID,
                SERVICE_DATE,
                startTime,
                endTime,
                10,
                5,
                0,
                0,
                1,
                6,
                true,
                7L);
        ReflectionTestUtils.setField(bucket, "id", id);
        return bucket;
    }
}
