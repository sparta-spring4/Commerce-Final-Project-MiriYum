package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
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
import com.miriyum.domain.reservation.port.ReservationTemporaryMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldCommand;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationTemporaryMenuHoldSelection;
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
import java.time.temporal.ChronoUnit;
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
    private ReservationTemporaryMenuHoldPort temporaryMenuHoldPort;

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
                temporaryMenuHoldPort,
                consumerAccountService,
                storeEligibilityService,
                timeResolutionService,
                cancellationPolicySelector,
                Clock.fixed(NOW, ZoneOffset.UTC),
                () -> {
                    auditIdGenerationCount.incrementAndGet();
                    return AUDIT_UUID;
                });
        lenient().when(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .thenReturn(noTemporaryMenuHold());
    }

    @Test
    @DisplayName("생성 명령은 메뉴 선택을 정규화해 불변 오름차순 목록으로 보관한다")
    void createCommandCanonicalizesMenuSelectionsAtItsBoundary() {
        ReservationHoldContracts.CreateCommand command = commandWithSelections(List.of(
                new ReservationTemporaryMenuHoldSelection(9L, 2),
                new ReservationTemporaryMenuHoldSelection(3L, 1),
                new ReservationTemporaryMenuHoldSelection(9L, 4)));

        List<ReservationTemporaryMenuHoldSelection> selections = command.menuSelections();

        assertThat(selections).containsExactly(
                new ReservationTemporaryMenuHoldSelection(3L, 1),
                new ReservationTemporaryMenuHoldSelection(9L, 6));
        assertThatThrownBy(() -> selections.add(
                new ReservationTemporaryMenuHoldSelection(10L, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("null과 빈 메뉴 선택은 같은 불변 빈 목록으로 정규화한다")
    void createCommandCanonicalizesNullAndEmptyMenuSelections() {
        ReservationHoldContracts.CreateCommand nullSelections = commandWithSelections(null);
        ReservationHoldContracts.CreateCommand emptySelections = commandWithSelections(List.of());

        assertThat(nullSelections.menuSelections()).isEmpty();
        assertThat(emptySelections.menuSelections()).isEmpty();
        assertThatThrownBy(() -> nullSelections.menuSelections().add(
                new ReservationTemporaryMenuHoldSelection(3L, 1)))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("메뉴별 합계 100 초과와 int overflow는 replay 조회 전에 거절한다")
    void createCommandRejectsExcessAndOverflowBeforeCollaborators() {
        assertThatThrownBy(() -> commandWithSelections(List.of(
                new ReservationTemporaryMenuHoldSelection(3L, 60),
                new ReservationTemporaryMenuHoldSelection(3L, 41))))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> commandWithSelections(List.of(
                new ReservationTemporaryMenuHoldSelection(3L, Integer.MAX_VALUE),
                new ReservationTemporaryMenuHoldSelection(3L, 1))))
                .isInstanceOf(IllegalArgumentException.class)
                .hasCauseInstanceOf(ArithmeticException.class);

        then(holdRepository).shouldHaveNoInteractions();
        then(temporaryMenuHoldPort).shouldHaveNoInteractions();
        verifyNoFreshInteractions();
    }

    @Test
    @DisplayName("null 메뉴 항목과 양수가 아닌 메뉴 ID·수량은 collaborator 전에 거절한다")
    void createCommandRejectsNullAndNonPositiveSelectionsBeforeCollaborators() {
        assertThatThrownBy(() -> commandWithSelections(java.util.Arrays.asList(
                new ReservationTemporaryMenuHoldSelection(3L, 1), null)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldSelection(0L, 1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ReservationTemporaryMenuHoldSelection(3L, 0))
                .isInstanceOf(IllegalArgumentException.class);

        then(holdRepository).shouldHaveNoInteractions();
        then(temporaryMenuHoldPort).shouldHaveNoInteractions();
        verifyNoFreshInteractions();
    }

    @Test
    @DisplayName("초기 생성 replay는 fresh 검증 전에 저장된 메뉴 의미를 확인한다")
    void initialCreationReplayVerifiesPersistedMenuMeaningBeforeFreshWork() {
        ReservationHold existing = existingHold(CREATION_COMMAND_ID);
        List<ReservationTemporaryMenuHoldSelection> selections = List.of(
                new ReservationTemporaryMenuHoldSelection(3L, 2));
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID)).willReturn(Optional.of(existing));
        given(temporaryMenuHoldPort.verifyCreationReplay(any()))
                .willReturn(presentTemporaryMenuHold());

        ReservationHoldContracts.Result result = service.create(
                commandWithSelections(selections));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        ArgumentCaptor<ReservationTemporaryMenuHoldCommand.Replay> replayCaptor =
                ArgumentCaptor.forClass(ReservationTemporaryMenuHoldCommand.Replay.class);
        InOrder order = inOrder(holdRepository, temporaryMenuHoldPort);
        order.verify(holdRepository).findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID);
        order.verify(temporaryMenuHoldPort).verifyCreationReplay(replayCaptor.capture());
        assertThat(replayCaptor.getValue().reservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(replayCaptor.getValue().selections()).containsExactlyElementsOf(selections);
        verifyNoFreshInteractions();
    }

    @Test
    @DisplayName("Store 잠금 뒤 동시 생성 replay도 저장된 메뉴 의미를 확인한다")
    void concurrentCreationReplayVerifiesPersistedMenuMeaning() {
        ReservationHold existing = existingHold(CREATION_COMMAND_ID);
        List<ReservationTemporaryMenuHoldSelection> selections = List.of(
                new ReservationTemporaryMenuHoldSelection(3L, 2));
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID))
                .willReturn(Optional.empty(), Optional.of(existing));
        given(consumerAccountService.getReservationContact(CONSUMER_ID))
                .willReturn(new ReservationContactResult("opaque-contact-ref", true));
        given(storeEligibilityService.requireReservationTransactionEligibility(STORE_ID))
                .willReturn(new StoreReservationTransactionEligibility(STORE_ID, "store"));
        given(temporaryMenuHoldPort.verifyCreationReplay(any()))
                .willReturn(presentTemporaryMenuHold());

        ReservationHoldContracts.Result result = service.create(
                commandWithSelections(selections));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        then(temporaryMenuHoldPort).should().verifyCreationReplay(
                new ReservationTemporaryMenuHoldCommand.Replay(HOLD_ID, selections));
        then(storeScheduleService).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).shouldHaveNoInteractions();
        then(warningTaskRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("replay 메뉴 의미 불일치는 COMMON_007이고 fresh 효과가 없다")
    void replayMenuMismatchReturnsCommon007WithoutFreshEffects() {
        ReservationHold existing = existingHold(CREATION_COMMAND_ID);
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, CREATION_COMMAND_ID)).willReturn(Optional.of(existing));
        given(temporaryMenuHoldPort.verifyCreationReplay(any()))
                .willThrow(new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        assertThatThrownBy(() -> service.create(commandWithSelections(List.of(
                new ReservationTemporaryMenuHoldSelection(3L, 2)))))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        verifyNoFreshInteractions();
        then(temporaryMenuHoldPort).should(never()).create(any());
    }

    @Test
    @DisplayName("fresh 빈 메뉴 선택은 임시 MenuHold 포트에 영속 효과를 요청하지 않는다")
    void freshEmptyMenuSelectionsHaveNoTemporaryMenuHoldEffects() {
        List<ReservationCapacityBucket> buckets = List.of(
                bucket(301L, LocalTime.of(18, 0), LocalTime.of(19, 0)),
                bucket(302L, LocalTime.of(19, 0), LocalTime.of(19, 30)));
        stubFreshPath(buckets);
        stubHoldSaveWithGeneratedId();

        ReservationHoldContracts.Result result = service.create(commandWithSelections(List.of()));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        then(temporaryMenuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("fresh 메뉴 선택은 모든 capacity 점유 뒤 저장된 Hold ID·만료와 정규화 선택을 전달한다")
    void freshMenuSelectionsCreateTemporaryMenuHoldAfterCapacityOccupation() {
        List<ReservationCapacityBucket> buckets = List.of(
                bucket(301L, LocalTime.of(18, 0), LocalTime.of(19, 0)),
                bucket(302L, LocalTime.of(19, 0), LocalTime.of(19, 30)));
        stubFreshPath(buckets);
        stubHoldSaveWithGeneratedId();
        given(temporaryMenuHoldPort.create(any())).willReturn(presentTemporaryMenuHold());
        List<ReservationTemporaryMenuHoldSelection> input = List.of(
                new ReservationTemporaryMenuHoldSelection(9L, 2),
                new ReservationTemporaryMenuHoldSelection(3L, 1),
                new ReservationTemporaryMenuHoldSelection(9L, 4));

        ReservationHoldContracts.Result result = service.create(commandWithSelections(input));

        ArgumentCaptor<ReservationTemporaryMenuHoldCommand.Create> createCaptor =
                ArgumentCaptor.forClass(ReservationTemporaryMenuHoldCommand.Create.class);
        InOrder order = inOrder(capacityBucketRepository, holdRepository,
                allocationRepository, temporaryMenuHoldPort, auditRepository,
                warningTaskRepository);
        order.verify(capacityBucketRepository).findLatestPolicyBucketsOverlappingForUpdate(
                STORE_ID, SERVICE_DATE, START_TIME, LocalTime.of(19, 15));
        order.verify(holdRepository).saveAndFlush(any(ReservationHold.class));
        order.verify(allocationRepository).saveAll(anyList());
        order.verify(temporaryMenuHoldPort).create(createCaptor.capture());
        order.verify(auditRepository).save(any(ReservationHoldTransitionAudit.class));
        order.verify(warningTaskRepository).save(any(ReservationHoldWarningTask.class));
        ReservationTemporaryMenuHoldCommand.Create sent = createCaptor.getValue();
        assertThat(sent.reservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(sent.storeId()).isEqualTo(STORE_ID);
        assertThat(sent.consumerAccountId()).isEqualTo(CONSUMER_ID);
        assertThat(sent.serviceDate()).isEqualTo(SERVICE_DATE);
        assertThat(sent.startTime()).isEqualTo(START_TIME);
        assertThat(sent.endDate()).isEqualTo(SERVICE_DATE);
        assertThat(sent.endTime()).isEqualTo(LocalTime.of(19, 0));
        assertThat(sent.startAt()).isEqualTo(result.startAt());
        assertThat(sent.serviceEndAt()).isEqualTo(result.serviceEndAt());
        assertThat(sent.expiresAt()).isEqualTo(result.expiresAt());
        assertThat(sent.selections()).containsExactly(
                new ReservationTemporaryMenuHoldSelection(3L, 1),
                new ReservationTemporaryMenuHoldSelection(9L, 6));
    }

    @Test
    @DisplayName("MenuHold 실패는 audit·warning을 막고 앞선 capacity는 transaction rollback에 맡긴다")
    void temporaryMenuHoldFailureStopsAuditAndWarningWithoutManualCompensation() {
        List<ReservationCapacityBucket> buckets = List.of(
                bucket(301L, LocalTime.of(18, 0), LocalTime.of(19, 0)),
                bucket(302L, LocalTime.of(19, 0), LocalTime.of(19, 30)));
        stubFreshPath(buckets);
        stubHoldSaveWithGeneratedId();
        ServiceException failure = new ServiceException(
                com.miriyum.domain.menuhold.error.MenuHoldErrorCode.INSUFFICIENT_QUANTITY);
        given(temporaryMenuHoldPort.create(any())).willThrow(failure);

        assertThatThrownBy(() -> service.create(commandWithSelections(List.of(
                new ReservationTemporaryMenuHoldSelection(3L, 2)))))
                .isSameAs(failure);

        assertThat(buckets).allSatisfy(bucket -> {
            assertThat(bucket.getOccupiedPeople()).isEqualTo(3);
            assertThat(bucket.getOccupiedTeams()).isOne();
        });
        then(auditRepository).shouldHaveNoInteractions();
        then(warningTaskRepository).shouldHaveNoInteractions();
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
    @DisplayName("DB collation이 찾은 case·accent 변형 생성 command는 exact ID가 아니므로 COMMON_007이다")
    void creationReplayRejectsCaseAndAccentVariantCommandId() {
        String storedCommandId = "Hold-Create-É";
        String requestedCommandId = "hold-create-e";
        ReservationHold existing = existingHold(storedCommandId);
        given(holdRepository.findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, requestedCommandId)).willReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.create(
                command(STORE_ID, null, requestedCommandId)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(holdRepository).should().findByConsumerAccountIdAndCreationCommandId(
                CONSUMER_ID, requestedCommandId);
        then(holdRepository).shouldHaveNoMoreInteractions();
        verifyNoFreshInteractions();
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
    @DisplayName("fresh 생성은 중앙 시각을 microsecond로 한 번 절삭해 Hold·audit·warning에 공유한다")
    void freshCreationTruncatesOneCentralInstantToMicrosForEverySnapshot() {
        Instant nanosecondNow = Instant.parse("2026-08-03T00:00:00.123456789Z");
        Instant expectedCreatedAt = nanosecondNow.truncatedTo(ChronoUnit.MICROS);
        service = serviceWithClock(Clock.fixed(nanosecondNow, ZoneOffset.UTC));
        List<ReservationCapacityBucket> buckets = List.of(
                bucket(301L, LocalTime.of(18, 0), LocalTime.of(19, 0)),
                bucket(302L, LocalTime.of(19, 0), LocalTime.of(19, 30))
        );
        stubFreshPath(buckets);
        stubHoldSaveWithGeneratedId();

        ReservationHoldContracts.Result result = service.create(
                command(STORE_ID, null, CREATION_COMMAND_ID));

        assertThat(result.createdAt()).isEqualTo(expectedCreatedAt);
        assertThat(result.expiresAt()).isEqualTo(expectedCreatedAt.plusSeconds(600));
        ArgumentCaptor<ReservationHoldTransitionAudit> auditCaptor =
                ArgumentCaptor.forClass(ReservationHoldTransitionAudit.class);
        then(auditRepository).should().save(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getRequestedAt()).isEqualTo(expectedCreatedAt);
        assertThat(auditCaptor.getValue().getOccurredAt()).isEqualTo(expectedCreatedAt);
        ArgumentCaptor<ReservationHoldWarningTask> warningCaptor =
                ArgumentCaptor.forClass(ReservationHoldWarningTask.class);
        then(warningTaskRepository).should().save(warningCaptor.capture());
        assertThat(warningCaptor.getValue().getCreatedAt()).isEqualTo(expectedCreatedAt);
        assertThat(warningCaptor.getValue().getWarningDueAt())
                .isEqualTo(expectedCreatedAt.plusSeconds(480));
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
    @DisplayName("종결 명령은 trailing finalReservationId scalar 계약을 공개한다")
    void transitionCommandExposesTrailingFinalReservationIdContract() {
        assertThat(ReservationHoldContracts.TransitionCommand.class.getRecordComponents())
                .extracting(java.lang.reflect.RecordComponent::getName)
                .containsExactly(
                        "reservationHoldId",
                        "targetStatus",
                        "operationId",
                        "actorType",
                        "actorId",
                        "requestedAt",
                        "finalReservationId");
    }

    @Test
    @DisplayName("종결 DTO는 non-confirm 연결과 양수가 아닌 confirm 연결을 직접 거절한다")
    void transitionCommandRejectsMalformedFinalLinkageAtItsBoundary() {
        assertThatThrownBy(() -> transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RELEASED,
                TRANSITION_OPERATION_ID,
                91L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                0L))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                null).finalReservationId()).isNull();
        assertThat(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                91L).finalReservationId()).isEqualTo(91L);
    }

    @Test
    @DisplayName("종결은 ReservationHold 잠금 직후 MenuHold를 선잠그고 capacity를 조회한다")
    void transitionPrelocksMenuImmediatelyAfterHoldLockBeforeCapacityLookup() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationCapacityBucket bucket = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold, List.of(allocation(301L)), List.of(301L), List.of(bucket));

        service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID));

        InOrder order = inOrder(
                holdRepository, auditRepository, temporaryMenuHoldPort,
                allocationRepository, capacityBucketRepository);
        order.verify(holdRepository).findByIdForUpdate(HOLD_ID);
        order.verify(auditRepository).findByCommandId(TRANSITION_OPERATION_ID);
        order.verify(temporaryMenuHoldPort).lockForTransition(HOLD_ID);
        order.verify(allocationRepository)
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(HOLD_ID);
    }

    @Test
    @DisplayName("menu-present release restores capacity before applying the MenuHold release")
    void menuPresentReleaseOrdersHoldMenuCapacityRestoreThenMenuApply() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationCapacityBucket bucket = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold, List.of(allocation(301L)), List.of(301L), List.of(bucket));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(presentTemporaryMenuHold());
        given(temporaryMenuHoldPort.applyTransition(any())).willAnswer(invocation -> {
            assertThat(bucket.getOccupiedPeople()).isZero();
            assertThat(bucket.getOccupiedTeams()).isZero();
            return new ReservationTemporaryMenuHoldResult(
                    ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                    ReservationTemporaryMenuHoldResult.State.RELEASED,
                    null);
        });

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        InOrder order = inOrder(
                holdRepository,
                temporaryMenuHoldPort,
                allocationRepository,
                capacityBucketRepository);
        order.verify(holdRepository).findByIdForUpdate(HOLD_ID);
        order.verify(temporaryMenuHoldPort).lockForTransition(HOLD_ID);
        order.verify(allocationRepository)
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(HOLD_ID);
        order.verify(capacityBucketRepository).findAllByIdInForUpdate(List.of(301L));
        order.verify(temporaryMenuHoldPort).applyTransition(any());
        order.verify(holdRepository).saveAndFlush(hold);
    }

    @Test
    @DisplayName("menu-present expiry restores capacity before applying the MenuHold expiry")
    void menuPresentExpiryOrdersHoldMenuCapacityRestoreThenMenuApply() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID, NOW.minusSeconds(600));
        ReservationCapacityBucket bucket = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold, List.of(allocation(301L)), List.of(301L), List.of(bucket));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(presentTemporaryMenuHold());
        given(temporaryMenuHoldPort.applyTransition(any())).willAnswer(invocation -> {
            assertThat(bucket.getOccupiedPeople()).isZero();
            assertThat(bucket.getOccupiedTeams()).isZero();
            return new ReservationTemporaryMenuHoldResult(
                    ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                    ReservationTemporaryMenuHoldResult.State.EXPIRED,
                    null);
        });

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.EXPIRED, TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        InOrder order = inOrder(
                holdRepository,
                temporaryMenuHoldPort,
                allocationRepository,
                capacityBucketRepository);
        order.verify(holdRepository).findByIdForUpdate(HOLD_ID);
        order.verify(temporaryMenuHoldPort).lockForTransition(HOLD_ID);
        order.verify(allocationRepository)
                .findAllByReservationHoldIdOrderByCapacityBucketIdAsc(HOLD_ID);
        order.verify(capacityBucketRepository).findAllByIdInForUpdate(List.of(301L));
        order.verify(temporaryMenuHoldPort).applyTransition(any());
        order.verify(holdRepository).saveAndFlush(hold);
    }

    @Test
    @DisplayName("메뉴 없는 점유 유지 전이는 MenuHold 부재를 잠그고 apply와 capacity를 생략한다")
    void retainingNoMenuTransitionLocksPresenceAndSkipsApplyAndCapacity() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        stubFreshTransition(hold);

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.RECONCILIATION_REQUIRED);
        then(temporaryMenuHoldPort).should().lockForTransition(HOLD_ID);
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("audit replay는 반환 전에 영속 MenuHold 부재를 확인하고 어떤 상태도 추가하지 않는다")
    void transitionReplayVerifiesPersistentMenuAbsenceBeforeReturning() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));

        service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID));

        InOrder order = inOrder(auditRepository, temporaryMenuHoldPort);
        order.verify(auditRepository).findByCommandId(TRANSITION_OPERATION_ID);
        order.verify(temporaryMenuHoldPort).lockForTransition(HOLD_ID);
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("MenuHold apply 실패는 그대로 전파되어 Hold 전이와 audit을 커밋하지 않는다")
    void temporaryMenuHoldApplyFailureStopsHoldTransitionAndAudit() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        stubFreshTransition(hold);
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(presentTemporaryMenuHold());
        ServiceException failure = new ServiceException(
                com.miriyum.domain.menuhold.error.MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        given(temporaryMenuHoldPort.applyTransition(any())).willThrow(failure);

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID)))
                .isSameAs(failure);

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        then(auditRepository).should(never()).save(any());
        then(holdRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("허용되지 않은 점유 유지 전이는 MenuHold apply 전에 Hold 상태로 거절한다")
    void invalidRetainingTransitionFailsBeforeTemporaryMenuHoldApply() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        stubFreshTransition(hold);
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(new ReservationTemporaryMenuHoldResult(
                        ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                        ReservationTemporaryMenuHoldResult.State.RECONCILIATION_REQUIRED,
                        null));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION));

        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("메뉴가 있는 확정은 공급된 기존 Reservation ID를 그대로 연결하고 Reservation을 만들지 않는다")
    void menuConfirmationLinksSuppliedExistingReservationIdWithoutCreatingReservation() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        stubFreshTransition(hold);
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(presentTemporaryMenuHold());
        given(temporaryMenuHoldPort.applyTransition(any()))
                .willReturn(confirmedTemporaryMenuHold(91L));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                91L));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        ArgumentCaptor<ReservationTemporaryMenuHoldCommand.ApplyTransition> captor =
                ArgumentCaptor.forClass(
                        ReservationTemporaryMenuHoldCommand.ApplyTransition.class);
        then(temporaryMenuHoldPort).should().applyTransition(captor.capture());
        assertThat(captor.getValue().target())
                .isEqualTo(ReservationTemporaryMenuHoldCommand.Target.CONFIRM);
        assertThat(captor.getValue().finalReservationId()).isEqualTo(91L);
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("메뉴가 있는 확정은 양의 final Reservation ID 없이는 상태를 바꾸지 않는다")
    void menuConfirmationRequiresPositiveFinalReservationIdBeforeMutation() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        stubFreshTransition(hold);
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(presentTemporaryMenuHold());

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("메뉴 없는 확정은 final Reservation ID를 거절하고 inventory apply를 호출하지 않는다")
    void noMenuConfirmationRequiresNullFinalReservationIdWithoutInventoryMutation() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        stubFreshTransition(hold);

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                91L)))
                .isInstanceOf(IllegalArgumentException.class);

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("non-confirm 목표의 final Reservation ID는 replay 조회 전에 거절한다")
    void nonConfirmTransitionRejectsFinalReservationIdBeforeCollaborators() {
        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RELEASED,
                TRANSITION_OPERATION_ID,
                91L)))
                .isInstanceOf(IllegalArgumentException.class);

        then(auditRepository).shouldHaveNoInteractions();
        then(holdRepository).shouldHaveNoInteractions();
        then(temporaryMenuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("초기 audit replay는 다른 최종 Reservation 연결을 COMMON_007로 거절한다")
    void initialAuditReplayRejectsDifferentFinalReservationLinkage() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.confirm();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(confirmedTemporaryMenuHold(91L));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                92L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("Hold 잠금 후 concurrent replay도 다른 최종 Reservation 연결을 COMMON_007로 거절한다")
    void concurrentAuditReplayRejectsDifferentFinalReservationLinkage() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.confirm();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.empty(), Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(confirmedTemporaryMenuHold(91L));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                92L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("audit replay의 영속 메뉴 부재는 non-null 최종 연결과 같지 않다")
    void auditReplayRejectsFinalLinkageWhenMenuIsPersistentlyAbsent() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.confirm();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                91L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
    }

    @Test
    @DisplayName("audit replay는 영속 MenuHold가 목표 상태에 수렴하지 않았으면 COMMON_007이다")
    void auditReplayRejectsMenuHoldStateThatDidNotConvergeToTarget() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.release();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RELEASED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(presentTemporaryMenuHold());

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RELEASED,
                TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("replay rejects a group state that precedes the audited transition")
    void auditReplayRejectsGroupStateThatPrecedesAuditedTransition() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(presentTemporaryMenuHold());

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
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
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                "  " + TRANSITION_OPERATION_ID + "  "));

        assertThat(result.reservationHoldId()).isEqualTo(HOLD_ID);
        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        then(holdRepository).should().findByIdForUpdate(HOLD_ID);
        then(holdRepository).should(never()).findById(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("initial replay of reconciliation returns a later menu-present confirmation")
    void initialReconciliationReplayReturnsLaterMenuPresentConfirmation() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        hold.confirm();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(confirmedTemporaryMenuHold(91L));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        then(holdRepository).should(never()).findById(any());
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("finalLinkedTerminalMenuStates")
    @DisplayName("confirm replay는 최종 예약의 합법적인 후속 MenuHold 종결 상태를 반환한다")
    void confirmationReplayReturnsFinalLinkedTerminalMenuState(
            ReservationTemporaryMenuHoldResult.State terminalState
    ) {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID);
        hold.confirm();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(new ReservationTemporaryMenuHoldResult(
                        ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                        terminalState,
                        91L));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                91L));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("finalLinkedTerminalMenuStates")
    @DisplayName("종결된 MenuHold의 confirm replay도 다른 최종 Reservation 연결은 거절한다")
    void confirmationReplayRejectsDifferentFinalLinkageInTerminalMenuState(
            ReservationTemporaryMenuHoldResult.State terminalState
    ) {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID);
        hold.confirm();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(new ReservationTemporaryMenuHoldResult(
                        ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                        terminalState,
                        91L));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                92L)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(auditRepository).should(never()).save(any());
    }

    @ParameterizedTest
    @MethodSource("finalLinkedTerminalMenuStates")
    @DisplayName("reconciliation replay는 확정 뒤 최종 예약의 후속 MenuHold 종결 상태를 반환한다")
    void reconciliationReplayReturnsFinalLinkedTerminalMenuState(
            ReservationTemporaryMenuHoldResult.State terminalState
    ) {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        hold.confirm();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(new ReservationTemporaryMenuHoldResult(
                        ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                        terminalState,
                        91L));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("initial replay locks the latest Hold before the MenuHold snapshot")
    void initialReplayLocksLatestHoldBeforeMenuHoldSnapshot() {
        ReservationHold staleHold = existingHold(CREATION_COMMAND_ID);
        staleHold.requireReconciliation();
        ReservationHoldTransitionAudit audit = transitionAudit(
                staleHold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        ReservationHold latestHold = existingHold(CREATION_COMMAND_ID);
        latestHold.requireReconciliation();
        latestHold.confirm();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(latestHold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(confirmedTemporaryMenuHold(91L));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        InOrder order = inOrder(auditRepository, holdRepository, temporaryMenuHoldPort);
        order.verify(auditRepository).findByCommandId(TRANSITION_OPERATION_ID);
        order.verify(holdRepository).findByIdForUpdate(HOLD_ID);
        order.verify(temporaryMenuHoldPort).lockForTransition(HOLD_ID);
        then(holdRepository).should(never()).findById(any());
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
        then(holdRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("initial replay of reconciliation returns a later menu-present release")
    void initialReconciliationReplayReturnsLaterMenuPresentRelease() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        hold.release();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(new ReservationTemporaryMenuHoldResult(
                        ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                        ReservationTemporaryMenuHoldResult.State.RELEASED,
                        null));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        then(holdRepository).should(never()).findById(any());
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("concurrent replay of reconciliation returns a later menu-present confirmation")
    void concurrentReconciliationReplayReturnsLaterMenuPresentConfirmation() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        hold.confirm();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.empty(), Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(confirmedTemporaryMenuHold(91L));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.CONFIRMED);
        then(auditRepository).should(times(2)).findByCommandId(TRANSITION_OPERATION_ID);
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("concurrent replay of reconciliation returns a later menu-present release")
    void concurrentReconciliationReplayReturnsLaterMenuPresentRelease() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        hold.requireReconciliation();
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID);
        hold.release();
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.empty(), Optional.of(audit));
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        given(temporaryMenuHoldPort.lockForTransition(HOLD_ID))
                .willReturn(new ReservationTemporaryMenuHoldResult(
                        ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                        ReservationTemporaryMenuHoldResult.State.RELEASED,
                        null));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.RECONCILIATION_REQUIRED,
                TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        then(auditRepository).should(times(2)).findByCommandId(TRANSITION_OPERATION_ID);
        then(temporaryMenuHoldPort).should(never()).applyTransition(any());
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("DB collation이 찾은 case·accent 변형 transition operation은 exact ID가 아니므로 COMMON_007이다")
    void transitionReplayRejectsCaseAndAccentVariantOperationId() {
        String storedOperationId = "Hold-Transition-É";
        String requestedOperationId = "hold-transition-e";
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                storedOperationId);
        given(auditRepository.findByCommandId(requestedOperationId))
                .willReturn(Optional.of(audit));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                requestedOperationId)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(holdRepository).shouldHaveNoInteractions();
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

    @ParameterizedTest(name = "{0}")
    @MethodSource("transitionReplayAuditMeaningConflicts")
    @DisplayName("operation의 감사 명령 의미가 하나라도 다르면 COMMON_007이고 부작용이 없다")
    void transitionReplayRejectsDifferentAuditMeaningWithoutSideEffects(
            String ignoredDescription,
            String requestedActorType,
            Long requestedActorId,
            Instant requestedAt
    ) {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationHoldTransitionAudit audit = transitionAudit(
                hold,
                ReservationHoldStatus.ACTIVE,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                "PAYMENT",
                41L,
                NOW.minusSeconds(1));
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.of(audit));

        assertThatThrownBy(() -> service.transition(new ReservationHoldContracts.TransitionCommand(
                HOLD_ID,
                ReservationHoldStatus.CONFIRMED,
                TRANSITION_OPERATION_ID,
                requestedActorType,
                requestedActorId,
                requestedAt)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.IDEMPOTENCY_KEY_REUSED));

        then(holdRepository).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
        then(allocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(consumerAccountService).shouldHaveNoInteractions();
        then(storeEligibilityService).shouldHaveNoInteractions();
        then(storeScheduleService).shouldHaveNoInteractions();
        then(timePolicyRepository).shouldHaveNoInteractions();
        then(intervalValidationService).shouldHaveNoInteractions();
        then(auditRepository).should(never()).save(any());
        then(warningTaskRepository).shouldHaveNoInteractions();
        assertThat(auditIdGenerationCount).hasValue(0);
    }

    private static Stream<Arguments> transitionReplayAuditMeaningConflicts() {
        Instant requestedAt = NOW.minusSeconds(1);
        return Stream.of(
                Arguments.of("different actor type", "WORKER", 41L, requestedAt),
                Arguments.of("different actor id", "PAYMENT", 42L, requestedAt),
                Arguments.of("different requested at", "PAYMENT", 41L,
                        requestedAt.plus(1, ChronoUnit.MICROS))
        );
    }

    private static Stream<ReservationTemporaryMenuHoldResult.State>
            finalLinkedTerminalMenuStates() {
        return Stream.of(
                ReservationTemporaryMenuHoldResult.State.RELEASED,
                ReservationTemporaryMenuHoldResult.State.FULFILLED);
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
    @DisplayName("merge 재게시 해제는 여러 original과 단일 latest 합집합을 정렬해 각각 한 번 복구한다")
    void releaseAfterPolicyMergeRestoresEveryOriginalAndLatestBucketOnce() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationCapacityBucket originalFirst = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(18, 30), 7L, 3, 1);
        ReservationCapacityBucket originalSecond = bucketWithOccupancy(
                302L, LocalTime.of(18, 30), LocalTime.of(19, 15), 7L, 3, 1);
        ReservationCapacityBucket latest = bucketWithOccupancy(
                401L, LocalTime.of(18, 0), LocalTime.of(19, 15), 9L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold,
                List.of(allocation(301L), allocation(302L)),
                List.of(401L),
                List.of(originalFirst, originalSecond, latest));

        service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID));

        assertThat(List.of(originalFirst, originalSecond, latest))
                .allSatisfy(bucket -> {
                    assertThat(bucket.getOccupiedPeople()).isZero();
                    assertThat(bucket.getOccupiedTeams()).isZero();
                });
        then(capacityBucketRepository).should().findAllByIdInForUpdate(
                List.of(301L, 302L, 401L));
        then(capacityBucketRepository).should(times(2)).findLatestPolicyVersion(
                STORE_ID, SERVICE_DATE);
        then(capacityBucketRepository).should(times(2))
                .findLatestPolicyBucketIdsOverlapping(
                        List.of(STORE_ID),
                        SERVICE_DATE,
                        START_TIME,
                        LocalTime.of(19, 15));
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
    @DisplayName("재게시 최신 정책에 겹치는 버킷이 없어도 RELEASED는 original 점유를 복구한다")
    void releaseAfterPublicationWithNoOverlappingLatestBucketRestoresOriginalOnly() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationCapacityBucket original = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold,
                List.of(allocation(301L)),
                List.of(),
                List.of(original));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.RELEASED);
        assertThat(original.getOccupiedPeople()).isZero();
        assertThat(original.getOccupiedTeams()).isZero();
        then(capacityBucketRepository).should().findAllByIdInForUpdate(List.of(301L));
    }

    @Test
    @DisplayName("재게시 최신 정책이 Hold 일부만 겹쳐도 EXPIRED는 original과 실제 겹침만 복구한다")
    void expiryAfterPublicationWithPartialLatestOverlapRestoresActualUnion() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID, NOW.minusSeconds(600));
        ReservationCapacityBucket original = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        ReservationCapacityBucket partialLatest = bucketWithOccupancy(
                401L, LocalTime.of(18, 30), LocalTime.of(19, 15), 9L, 3, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold,
                List.of(allocation(301L)),
                List.of(401L),
                List.of(original, partialLatest));

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.EXPIRED, TRANSITION_OPERATION_ID));

        assertThat(result.status()).isEqualTo(ReservationHoldStatus.EXPIRED);
        assertThat(List.of(original, partialLatest)).allSatisfy(bucket -> {
            assertThat(bucket.getOccupiedPeople()).isZero();
            assertThat(bucket.getOccupiedTeams()).isZero();
        });
        then(capacityBucketRepository).should().findAllByIdInForUpdate(
                List.of(301L, 401L));
    }

    @Test
    @DisplayName("union의 뒤 버킷 점유가 allocation보다 작으면 RESERVATION_008이고 앞 버킷도 변경하지 않는다")
    void restoreUnderflowFailsAsCapacityConflictBeforeAnyBucketMutation() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationCapacityBucket restorableFirst = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(18, 30), 7L, 3, 1);
        ReservationCapacityBucket underflowSecond = bucketWithOccupancy(
                302L, LocalTime.of(18, 30), LocalTime.of(19, 15), 7L, 2, 1);
        stubFreshTransition(hold);
        stubCapacityRelease(
                hold,
                List.of(allocation(301L), allocation(302L)),
                List.of(301L, 302L),
                List.of(restorableFirst, underflowSecond));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(
                                ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT));

        assertThat(restorableFirst.getOccupiedPeople()).isEqualTo(3);
        assertThat(restorableFirst.getOccupiedTeams()).isOne();
        assertThat(underflowSecond.getOccupiedPeople()).isEqualTo(2);
        assertThat(underflowSecond.getOccupiedTeams()).isOne();
        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        then(auditRepository).should(never()).save(any());
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

    @Test
    @DisplayName("union 잠금 뒤 최신 정책 version과 IDs가 바뀌면 복구와 상태 audit 없이 RESERVATION_008이다")
    void publicationChangeAfterUnionLockFailsClosedBeforeAnyMutation() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationCapacityBucket original = bucketWithOccupancy(
                301L, LocalTime.of(18, 0), LocalTime.of(19, 15), 7L, 3, 1);
        ReservationCapacityBucket observedLatest = bucketWithOccupancy(
                401L, LocalTime.of(18, 0), LocalTime.of(19, 15), 9L, 3, 1);
        stubFreshTransition(hold);
        given(allocationRepository.findAllByReservationHoldIdOrderByCapacityBucketIdAsc(
                HOLD_ID)).willReturn(List.of(allocation(301L)));
        given(capacityBucketRepository.findLatestPolicyVersion(STORE_ID, SERVICE_DATE))
                .willReturn(Optional.of(9L), Optional.of(10L));
        given(capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
                List.of(STORE_ID),
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 15)))
                .willReturn(List.of(401L), List.of(501L));
        given(capacityBucketRepository.findAllByIdInForUpdate(List.of(301L, 401L)))
                .willReturn(List.of(original, observedLatest));

        assertThatThrownBy(() -> service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.RELEASED, TRANSITION_OPERATION_ID)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(
                                        ReservationErrorCode.CAPACITY_CONFIGURATION_CONFLICT));

        assertThat(hold.getStatus()).isEqualTo(ReservationHoldStatus.ACTIVE);
        assertThat(original.getOccupiedPeople()).isEqualTo(3);
        assertThat(original.getOccupiedTeams()).isEqualTo(1);
        assertThat(observedLatest.getOccupiedPeople()).isEqualTo(3);
        assertThat(observedLatest.getOccupiedTeams()).isEqualTo(1);
        then(auditRepository).should(never()).save(any());
    }

    @Test
    @DisplayName("fresh transition은 audit 저장 뒤 Hold를 merge하고 영속 statusVersion을 반환한다")
    void freshTransitionMergesHoldAfterAuditBeforeResultExtraction() {
        ReservationHold hold = existingHold(CREATION_COMMAND_ID);
        ReservationHold persisted = existingHold(CREATION_COMMAND_ID);
        persisted.confirm();
        ReflectionTestUtils.setField(persisted, "statusVersion", 1L);
        stubFreshTransition(hold);
        given(holdRepository.saveAndFlush(hold)).willReturn(persisted);

        ReservationHoldContracts.Result result = service.transition(transitionCommand(
                HOLD_ID, ReservationHoldStatus.CONFIRMED, TRANSITION_OPERATION_ID));

        assertThat(persisted).isNotSameAs(hold);
        assertThat(hold.getStatusVersion()).isZero();
        assertThat(result.statusVersion()).isEqualTo(1L);
        InOrder order = inOrder(auditRepository, holdRepository);
        order.verify(auditRepository).save(any(ReservationHoldTransitionAudit.class));
        order.verify(holdRepository).saveAndFlush(hold);
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

    private ReservationHoldService serviceWithClock(Clock serviceClock) {
        ReservationTimeResolutionService timeResolutionService =
                new ReservationTimeResolutionService(
                        storeScheduleService,
                        intervalValidationService,
                        timePolicyRepository,
                        serviceClock);
        return new ReservationHoldService(
                holdRepository,
                reservationRepository,
                allocationRepository,
                auditRepository,
                warningTaskRepository,
                capacityBucketRepository,
                temporaryMenuHoldPort,
                consumerAccountService,
                storeEligibilityService,
                timeResolutionService,
                new ReservationCancellationPolicySelector(
                        new ReservationCancellationPolicyRegistry()),
                serviceClock,
                () -> {
                    auditIdGenerationCount.incrementAndGet();
                    return AUDIT_UUID;
                });
    }

    private void stubFreshTransition(ReservationHold hold) {
        given(auditRepository.findByCommandId(TRANSITION_OPERATION_ID))
                .willReturn(Optional.empty());
        given(holdRepository.findByIdForUpdate(HOLD_ID)).willReturn(Optional.of(hold));
        lenient().when(holdRepository.saveAndFlush(hold)).thenReturn(hold);
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
        long latestVersion = lockedBuckets.stream()
                .filter(bucket -> latestBucketIds.contains(bucket.getId()))
                .mapToLong(ReservationCapacityBucket::getPolicyVersion)
                .findFirst()
                .orElseGet(() -> latestBucketIds.equals(allocations.stream()
                        .map(ReservationHoldCapacityAllocation::getCapacityBucketId)
                        .toList()) ? 7L : 9L);
        given(capacityBucketRepository.findLatestPolicyVersion(STORE_ID, SERVICE_DATE))
                .willReturn(Optional.of(latestVersion));
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
        return transitionCommand(holdId, target, operationId, null);
    }

    private static ReservationHoldContracts.TransitionCommand transitionCommand(
            long holdId,
            ReservationHoldStatus target,
            String operationId,
            Long finalReservationId
    ) {
        return new ReservationHoldContracts.TransitionCommand(
                holdId,
                target,
                operationId,
                "SYSTEM",
                null,
                NOW.minusSeconds(1),
                finalReservationId);
    }

    private static ReservationHoldTransitionAudit transitionAudit(
            ReservationHold hold,
            ReservationHoldStatus before,
            ReservationHoldStatus after,
            String operationId
    ) {
        return transitionAudit(
                hold,
                before,
                after,
                operationId,
                "SYSTEM",
                null,
                NOW.minusSeconds(1));
    }

    private static ReservationHoldTransitionAudit transitionAudit(
            ReservationHold hold,
            ReservationHoldStatus before,
            ReservationHoldStatus after,
            String operationId,
            String actorType,
            Long actorId,
            Instant requestedAt
    ) {
        return ReservationHoldTransitionAudit.record(
                HOLD_ID,
                actorType,
                actorId,
                requestedAt,
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

    private static ReservationTemporaryMenuHoldResult presentTemporaryMenuHold() {
        return new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.ACTIVE,
                null);
    }

    private static ReservationTemporaryMenuHoldResult noTemporaryMenuHold() {
        return new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.NO_HOLD,
                null,
                null);
    }

    private static ReservationTemporaryMenuHoldResult confirmedTemporaryMenuHold(
            long finalReservationId
    ) {
        return new ReservationTemporaryMenuHoldResult(
                ReservationTemporaryMenuHoldResult.Presence.HOLD_PRESENT,
                ReservationTemporaryMenuHoldResult.State.CONFIRMED,
                finalReservationId);
    }

    private static ReservationHoldContracts.CreateCommand commandWithSelections(
            List<ReservationTemporaryMenuHoldSelection> selections
    ) {
        return new ReservationHoldContracts.CreateCommand(
                CONSUMER_ID,
                STORE_ID,
                SERVICE_DATE,
                START_TIME,
                null,
                2,
                1,
                0,
                CREATION_COMMAND_ID,
                selections);
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
