package com.miriyum.domain.reservation.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.assertj.core.api.Assertions.tuple;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.BDDMockito.willAnswer;
import static org.mockito.BDDMockito.willThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.auth.exception.AccountErrorCode;
import com.miriyum.domain.consumer.dto.contract.ReservationContactResult;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.notification.dto.source.NotificationSourceEventV1;
import com.miriyum.domain.notification.dto.source.NotificationTaskReceipt;
import com.miriyum.domain.notification.dto.source.NotificationPurpose;
import com.miriyum.domain.reservation.dto.request.ReservationAvailabilityCondition;
import com.miriyum.domain.reservation.dto.request.ReservationCreateRequest;
import com.miriyum.domain.reservation.dto.request.ReservationHistorySearchRequest;
import com.miriyum.domain.reservation.dto.request.ReservationMenuSelectionRequest;
import com.miriyum.domain.reservation.dto.request.ReservationPartyRequest;
import com.miriyum.domain.reservation.dto.request.ReservationTimeRequest;
import com.miriyum.domain.reservation.dto.request.StoreReservationSearchRequest;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityResult;
import com.miriyum.domain.reservation.dto.response.ReservationAvailabilityStatus;
import com.miriyum.domain.reservation.dto.response.ReservationDetailResponse;
import com.miriyum.domain.reservation.dto.response.ReservationHistoryPageResponse;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionResult;
import com.miriyum.domain.reservation.dto.response.ReservationTimeResolutionStatus;
import com.miriyum.domain.reservation.dto.response.StoreReservationPageResponse;
import com.miriyum.domain.reservation.entity.PartyComposition;
import com.miriyum.domain.reservation.entity.Reservation;
import com.miriyum.domain.reservation.entity.ReservationCancellationActorType;
import com.miriyum.domain.reservation.entity.ReservationCancellationAudit;
import com.miriyum.domain.reservation.entity.ReservationCancellationPolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationCapacityAllocation;
import com.miriyum.domain.reservation.entity.ReservationCapacityBucket;
import com.miriyum.domain.reservation.entity.ReservationContactSnapshot;
import com.miriyum.domain.reservation.entity.ReservationFulfillmentAudit;
import com.miriyum.domain.reservation.entity.ReservationStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyStatus;
import com.miriyum.domain.reservation.entity.ReservationTimePolicyVersion;
import com.miriyum.domain.reservation.entity.ReservationTimeSnapshot;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.notification.ReservationNotificationEventFactory;
import com.miriyum.domain.reservation.notification.ReservationNotificationPublisher;
import com.miriyum.domain.reservation.port.ReservationMenuHoldPort;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldCreateCommand;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldItemSnapshot;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldResult;
import com.miriyum.domain.reservation.port.dto.ReservationMenuHoldTerminationPresence;
import com.miriyum.domain.reservation.repository.ReservationCapacityBucketRepository;
import com.miriyum.domain.reservation.repository.ReservationCapacityAllocationRepository;
import com.miriyum.domain.reservation.repository.ReservationCancellationAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationFulfillmentAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyAuditRepository;
import com.miriyum.domain.reservation.repository.ReservationTimePolicyVersionRepository;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.domain.store.dto.contract.StoreReservationTransactionEligibility;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.schedule.dto.contract.StoreReservationWindowResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.service.StoreScheduleService;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyCommand;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.util.ReflectionUtils;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-03T00:00:00Z");
    private static final LocalDate SERVICE_DATE = LocalDate.of(2026, 8, 3);
    private static final LocalDate CAPACITY_SERVICE_DATE = LocalDate.of(2026, 8, 2);
    private static final LocalTime START_TIME = LocalTime.of(18, 0);
    private static final LocalTime CAPACITY_END_TIME = LocalTime.of(19, 0);
    private static final String CANCELLATION_KEY =
            "550e8400-e29b-41d4-a716-446655440000";
    private static final String CONSUMER_CANCELLATION_CORRELATION =
            "reservation-cancel:consumer:11:" + CANCELLATION_KEY;
    private static final String OPERATOR_CANCELLATION_CORRELATION =
            "reservation-cancel:store-operator:33:" + CANCELLATION_KEY;
    private static final long OPERATOR_ID = 33L;
    private static final long STORE_ID = 22L;
    private static final long RESERVATION_ID = 77L;
    private static final Instant REQUESTED_AT = NOW.minusSeconds(10);
    private static final Instant OCCURRED_AT = NOW;
    private static final String FULFILLMENT_KEY =
            "550e8400-e29b-41d4-a716-446655440000";
    private static final String FULFILLMENT_CORRELATION =
            "reservation-fulfill:store-operator:33:" + FULFILLMENT_KEY;

    @Mock
    private StoreScheduleService storeScheduleService;

    @Mock
    private StoreServiceIntervalValidationService storeServiceIntervalValidationService;

    @Mock
    private ReservationTimePolicyVersionRepository timePolicyRepository;

    @Mock
    private StoreService storeService;

    @Mock
    private IdempotencyExecutor idempotencyExecutor;

    @Mock
    private ReservationTimePolicyAuditRepository timePolicyAuditRepository;

    @Mock
    private ReservationCapacityBucketRepository capacityBucketRepository;

    @Mock
    private ReservationRepository reservationRepository;

    @Mock
    private ConsumerAccountService consumerAccountService;

    @Mock
    private ReservationMenuHoldPort menuHoldPort;

    @Mock
    private StoreTransactionEligibilityService storeTransactionEligibilityService;

    @Mock
    private ReservationCapacityAllocationRepository capacityAllocationRepository;

    @Mock
    private ReservationCancellationPolicySelector cancellationPolicySelector;

    @Mock
    private ReservationCancellationAuditRepository cancellationAuditRepository;

    @Mock
    private ReservationCancellationPolicyEvaluator cancellationPolicyEvaluator;

    @Mock
    private Clock clock;

    @Mock
    private ReservationFulfillmentAuditRepository fulfillmentAuditRepository;

    private ReservationService reservationService;
    private ReservationTimeResolutionService timeResolutionService;
    private List<NotificationSourceEventV1> notificationEvents;

    @BeforeEach
    void setUp() {
        lenient().when(clock.instant()).thenReturn(NOW);
        timeResolutionService = new ReservationTimeResolutionService(
                storeScheduleService,
                storeServiceIntervalValidationService,
                timePolicyRepository,
                clock);
        notificationEvents = new ArrayList<>();
        ReservationNotificationPublisher notificationPublisher =
                new ReservationNotificationPublisher(
                        new ReservationNotificationEventFactory(),
                        event -> {
                            notificationEvents.add(event);
                            return new NotificationTaskReceipt(
                                    Long.toString(500L + notificationEvents.size()), false
                            );
                        }
                );
        reservationService = new ReservationService(
                storeScheduleService,
                storeServiceIntervalValidationService,
                timePolicyRepository,
                storeService,
                idempotencyExecutor,
                timePolicyAuditRepository,
                new ObjectMapper(),
                clock,
                capacityBucketRepository,
                reservationRepository,
                consumerAccountService,
                menuHoldPort,
                timeResolutionService,
                storeTransactionEligibilityService,
                capacityAllocationRepository,
                cancellationPolicySelector,
                cancellationAuditRepository,
                cancellationPolicyEvaluator,
                fulfillmentAuditRepository,
                notificationPublisher
        );
    }

    @Test
    @DisplayName("새 예약 키에서 불가능한 연락처는 ACCOUNT_006으로 거절하고 자원을 변경하지 않는다")
    void rejectsUnavailableContactForNewKeyWithoutMutatingResources() {
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22",
                SERVICE_DATE,
                START_TIME,
                null,
                new ReservationPartyRequest(2, 0, 0),
                List.of()
        );
        given(consumerAccountService.getReservationContact(11L))
                .willReturn(new ReservationContactResult("", false));
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            return work.get();
        });

        assertThatThrownBy(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(AccountErrorCode.RESERVATION_CONTACT_REQUIRED));

        then(consumerAccountService).should().requireActiveAccount(11L);
        then(idempotencyExecutor).should(times(1)).execute(any(), any());
        then(storeTransactionEligibilityService).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @MethodSource("inactiveOrDeletedConsumerErrors")
    @DisplayName("현재 비활성 또는 삭제된 소비자는 replay 확인 전에 거절한다")
    void rejectsInactiveOrDeletedConsumerBeforeIdempotencyReplay(AuthErrorCode errorCode) {
        willThrow(new ServiceException(errorCode)).given(consumerAccountService)
                .requireActiveAccount(11L);

        assertThatThrownBy(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                creationRequest(2, 0, 0)
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(errorCode));

        then(idempotencyExecutor).shouldHaveNoInteractions();
        then(consumerAccountService).should(never()).getReservationContact(11L);
    }

    private static Stream<Arguments> inactiveOrDeletedConsumerErrors() {
        return Stream.of(
                Arguments.of(AuthErrorCode.ACCOUNT_RESTRICTED),
                Arguments.of(AuthErrorCode.ACCESS_TOKEN_INVALID));
    }

    @Test
    @DisplayName("메뉴 없는 예약은 CONFIRMED aggregate와 모든 버킷 배정을 저장하고 201 상세를 반환한다")
    void createsConfirmedReservationWithoutMenuHold() {
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22",
                SERVICE_DATE,
                START_TIME,
                null,
                new ReservationPartyRequest(2, 1, 0),
                List.of()
        );
        ReservationTimePolicyVersion policy = activePolicy(22L, 30, 60, 0);
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0),
                10, 3, 1, 1, 1, 6, true, 7L);
        ReflectionTestUtils.setField(bucket, "id", 301L);

        given(consumerAccountService.getReservationContact(11L))
                .willReturn(new ReservationContactResult("consumer:11:channel:primary", true));
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    new ObjectMapper().valueToTree(result.data()));
        });
        given(storeTransactionEligibilityService
                .requireReservationTransactionEligibility(22L))
                .willReturn(new StoreReservationTransactionEligibility(22L, "미리윰 식당"));
        given(storeScheduleService.resolveReservationWindows(
                List.of(22L), SERVICE_DATE, START_TIME))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        22L,
                        "Asia/Seoul",
                        LocalDateTime.of(SERVICE_DATE, LocalTime.of(17, 0)),
                        LocalDateTime.of(SERVICE_DATE, LocalTime.of(20, 0)))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                eq(java.util.Set.of(22L)),
                eq(ReservationTimePolicyStatus.ACTIVE),
                eq(ReservationTimePolicyStatus.SCHEDULED),
                eq(NOW)))
                .willReturn(List.of(policy));
        given(storeServiceIntervalValidationService.validateServiceIntervals(any()))
                .willAnswer(invocation -> {
                    List<StoreServiceIntervalRequest> requests = invocation.getArgument(0);
                    return requests.stream()
                            .map(interval -> StoreServiceIntervalResult.of(interval, true))
                            .toList();
                });
        given(reservationRepository.findConfirmedOverlappingForUpdate(
                11L,
                22L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(List.of());
        given(capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0)))
                .willReturn(List.of(bucket));
        given(cancellationPolicySelector.select())
                .willReturn(new ReservationCancellationPolicyVersion(1L));
        given(reservationRepository.saveAndFlush(any(Reservation.class)))
                .willAnswer(invocation -> {
                    Reservation saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 77L);
                    return saved;
                });
        lenient().when(menuHoldPort.findSnapshots(77L))
                .thenThrow(new IllegalStateException("menu-less creation must not query menu snapshots"));

        ReservationCreationCommandResult result = reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request
        );

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data()).satisfies(response -> {
            assertThat(response.reservationId()).isEqualTo("77");
            assertThat(response.storeName()).isEqualTo("미리윰 식당");
            assertThat(response.status()).isEqualTo("CONFIRMED");
            assertThat(response.menuSelections()).isEmpty();
        });
        assertThat(bucket.getOccupiedPeople()).isEqualTo(4);
        assertThat(bucket.getOccupiedTeams()).isEqualTo(2);
        ArgumentCaptor<Reservation> reservationCaptor = ArgumentCaptor.forClass(Reservation.class);
        then(reservationRepository).should().saveAndFlush(reservationCaptor.capture());
        assertThat(reservationCaptor.getValue().getContactSnapshot()
                .getNotificationTargetReference())
                .isEqualTo("consumer:11:channel:primary");
        assertThat(reservationCaptor.getValue().getCapacityPolicyVersion()).isEqualTo(7L);
        then(capacityAllocationRepository).should().saveAll(any());
        then(menuHoldPort).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        assertThat(notificationEvents).singleElement().satisfies(event -> {
            assertThat(event.purpose()).isEqualTo(NotificationPurpose.RESERVATION_CONFIRMED);
            assertThat(event.resourceId()).isEqualTo("77");
            assertThat(event.resourceVersion()).isEqualTo(1L);
            assertThat(event.correlationId())
                    .isEqualTo("550e8400-e29b-41d4-a716-446655440000");
        });
    }

    @Test
    @DisplayName("이미 지난 Asia/Seoul 예약 슬롯은 서비스 구간 검증과 자원 변경 전에 RESERVATION_002로 거절된다")
    void rejectsPastReservationSlotBeforeServiceIntervalAndMutation() {
        LocalDate pastServiceDate = LocalDate.of(2026, 8, 2);
        LocalTime pastStartTime = LocalTime.of(18, 0);
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22",
                pastServiceDate,
                pastStartTime,
                null,
                new ReservationPartyRequest(2, 0, 0),
                List.of(new ReservationMenuSelectionRequest("91", 1))
        );
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L, pastServiceDate, pastStartTime, LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, true, 7L);
        ReflectionTestUtils.setField(bucket, "id", 301L);

        given(consumerAccountService.getReservationContact(11L))
                .willReturn(new ReservationContactResult("consumer:11:channel:primary", true));
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    new ObjectMapper().valueToTree(result.data()));
        });
        given(storeTransactionEligibilityService
                .requireReservationTransactionEligibility(22L))
                .willReturn(new StoreReservationTransactionEligibility(22L, "Past-slot Store"));
        given(storeScheduleService.resolveReservationWindows(
                List.of(22L), pastServiceDate, pastStartTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        22L,
                        "Asia/Seoul",
                        LocalDateTime.of(pastServiceDate, LocalTime.of(17, 0)),
                        LocalDateTime.of(pastServiceDate, LocalTime.of(20, 0)))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                eq(java.util.Set.of(22L)),
                eq(ReservationTimePolicyStatus.ACTIVE),
                eq(ReservationTimePolicyStatus.SCHEDULED),
                eq(NOW)))
                .willReturn(List.of(activePolicy(22L, 30, 60, 0)));
        lenient().when(storeServiceIntervalValidationService.validateServiceIntervals(any()))
                .thenAnswer(invocation -> {
                    List<StoreServiceIntervalRequest> requests = invocation.getArgument(0);
                    return requests.stream()
                            .map(interval -> StoreServiceIntervalResult.of(interval, true))
                            .toList();
                });
        lenient().when(reservationRepository.findConfirmedOverlappingForUpdate(
                11L,
                22L,
                Instant.parse("2026-08-02T09:00:00Z"),
                Instant.parse("2026-08-02T10:00:00Z")))
                .thenReturn(List.of());
        lenient().when(capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                22L, pastServiceDate, pastStartTime, LocalTime.of(19, 0)))
                .thenReturn(List.of(bucket));
        lenient().when(cancellationPolicySelector.select())
                .thenReturn(new ReservationCancellationPolicyVersion(1L));
        lenient().when(reservationRepository.saveAndFlush(any(Reservation.class)))
                .thenAnswer(invocation -> {
                    Reservation saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 77L);
                    return saved;
                });
        lenient().when(menuHoldPort.create(any(ReservationMenuHoldCreateCommand.class)))
                .thenReturn(ReservationMenuHoldResult.confirmed(77L));
        lenient().when(menuHoldPort.findSnapshots(77L))
                .thenReturn(List.of(new ReservationMenuHoldItemSnapshot(91L, "Menu", 4_500L, 1)));

        assertThatThrownBy(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request
        )).isInstanceOfSatisfying(ServiceException.class, exception -> {
            assertThat(exception.getErrorCode())
                    .isEqualTo(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW);
            assertThat(exception.getErrorCode().getCode()).isEqualTo("RESERVATION_002");
            assertThat(exception.getErrorCode().getHttpStatus())
                    .isEqualTo(org.springframework.http.HttpStatus.CONFLICT);
        });

        then(storeServiceIntervalValidationService).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(capacityAllocationRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("직접 command 호출의 메뉴 수량 범위 위반도 COMMON_001로 자원 접근 전에 거절한다")
    void rejectsMalformedMenuQuantityBeforeContactLookup() {
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22",
                SERVICE_DATE,
                START_TIME,
                null,
                new ReservationPartyRequest(2, 0, 0),
                List.of(new ReservationMenuSelectionRequest("91", 101))
        );

        assertValidationFailure(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request
        ));

        then(consumerAccountService).shouldHaveNoInteractions();
        then(idempotencyExecutor).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("직접 command 호출의 메뉴 선택 20개 초과도 COMMON_001로 거절한다")
    void rejectsTooManyMenuSelectionsBeforeContactLookup() {
        List<ReservationMenuSelectionRequest> selections = java.util.stream.IntStream
                .rangeClosed(1, 21)
                .mapToObj(menuId -> new ReservationMenuSelectionRequest(
                        Integer.toString(menuId), 1))
                .toList();
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22", SERVICE_DATE, START_TIME, null,
                new ReservationPartyRequest(2, 0, 0), selections);

        assertValidationFailure(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request));

        then(consumerAccountService).shouldHaveNoInteractions();
        then(idempotencyExecutor).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("관대한 ZoneOffset parser가 받는 비정규 startOffset도 COMMON_001로 거절한다")
    void rejectsNonCanonicalStartOffsetBeforeContactLookup() {
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22", SERVICE_DATE, START_TIME, "+1",
                new ReservationPartyRequest(2, 0, 0), List.of());

        assertValidationFailure(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request));

        then(consumerAccountService).shouldHaveNoInteractions();
        then(idempotencyExecutor).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("중복 메뉴 합산과 ID 정렬 지문 replay는 연락처가 사라져도 최초 결과를 재생한다")
    void replaysCanonicalMenuFingerprintWithoutRecheckingContact() {
        ReservationCreateRequest first = new ReservationCreateRequest(
                "22", SERVICE_DATE, START_TIME, null,
                new ReservationPartyRequest(2, 0, 0),
                List.of(
                        new ReservationMenuSelectionRequest("92", 2),
                        new ReservationMenuSelectionRequest("91", 1),
                        new ReservationMenuSelectionRequest("91", 2)));
        ReservationCreateRequest reordered = new ReservationCreateRequest(
                "22", SERVICE_DATE, START_TIME, null,
                new ReservationPartyRequest(2, 0, 0),
                List.of(
                        new ReservationMenuSelectionRequest("91", 3),
                        new ReservationMenuSelectionRequest("92", 2)));
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, true, 7L);
        ReflectionTestUtils.setField(bucket, "id", 301L);
        stubSuccessfulCreation(bucket, activePolicy(22L, 30, 60, 0));
        given(menuHoldPort.create(any(ReservationMenuHoldCreateCommand.class)))
                .willReturn(ReservationMenuHoldResult.confirmed(77L));
        given(menuHoldPort.findSnapshots(77L))
                .willReturn(List.of(
                        new ReservationMenuHoldItemSnapshot(91L, "아메리카노", 4_500L, 3),
                        new ReservationMenuHoldItemSnapshot(92L, "케이크", 7_000L, 2)));
        AtomicInteger contactLookups = new AtomicInteger();
        given(consumerAccountService.getReservationContact(11L)).willAnswer(invocation -> {
            if (contactLookups.getAndIncrement() == 0) {
                return new ReservationContactResult("consumer:11:channel:primary", true);
            }
            throw new ServiceException(AccountErrorCode.RESERVATION_CONTACT_REQUIRED);
        });
        AtomicReference<IdempotentOutcome> storedOutcome = new AtomicReference<>();
        willAnswer(invocation -> {
            IdempotentOutcome stored = storedOutcome.get();
            if (stored != null) {
                return new IdempotentOutcome(
                        true,
                        stored.httpStatus(),
                        stored.responseCode(),
                        stored.resourceType(),
                        stored.resourceId(),
                        stored.data());
            }
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            IdempotentOutcome created = new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    new ObjectMapper().valueToTree(result.data()));
            storedOutcome.set(created);
            return created;
        }).given(idempotencyExecutor).execute(any(), any());
        IdempotencyKey key = IdempotencyKey.parse(
                "550e8400-e29b-41d4-a716-446655440000");

        ReservationCreationCommandResult firstResult =
                reservationService.createReservation(11L, key, first);
        ReservationCreationCommandResult replayResult =
                reservationService.createReservation(11L, key, reordered);

        assertThat(firstResult).isEqualTo(replayResult);
        ArgumentCaptor<IdempotencyCommand> commands =
                ArgumentCaptor.forClass(IdempotencyCommand.class);
        then(idempotencyExecutor).should(times(2)).execute(commands.capture(), any());
        assertThat(commands.getAllValues())
                .extracting(IdempotencyCommand::requestFingerprint)
                .containsOnly(commands.getAllValues().getFirst().requestFingerprint());
        then(consumerAccountService).should(times(2)).requireActiveAccount(11L);
        then(consumerAccountService).should(times(1)).getReservationContact(11L);
        then(reservationRepository).should(times(1)).saveAndFlush(any(Reservation.class));
        then(menuHoldPort).should(times(1)).create(any(ReservationMenuHoldCreateCommand.class));
    }

    @Test
    @DisplayName("저장 당시 tzdb offset이 현재 규칙과 달라도 replay 응답은 저장 payload를 그대로 반환한다")
    void replaysStoredReservationOffsetsWithoutCurrentTimezoneRecalculation() {
        ObjectMapper mapper = new ObjectMapper();
        ObjectNode storedPayload = mapper.valueToTree(
                ReservationDetailResponse.from(reservation(77L, 11L), List.of()));
        storedPayload.put("serviceDate", "2026-11-01");
        storedPayload.put("timeZoneId", "America/New_York");
        storedPayload.put("startAt", "2026-11-01T01:30:00-03:00");
        storedPayload.put("serviceEndAt", "2026-11-01T01:45:00-03:00");
        given(idempotencyExecutor.execute(any(), any())).willReturn(
                new IdempotentOutcome(
                        true, 201, "SUCCESS", "RESERVATION", "77", storedPayload));

        ReservationCreationCommandResult replayed = reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                creationRequest(2, 0, 0));

        JsonNode replayPayload = mapper.valueToTree(replayed.data());
        assertThat(replayPayload).isEqualTo(storedPayload);
        assertThat(replayed.data().startAt().toString())
                .isEqualTo("2026-11-01T01:30-03:00");
        assertThat(replayed.data().serviceEndAt().toString())
                .isEqualTo("2026-11-01T01:45-03:00");
        then(consumerAccountService).should(never()).getReservationContact(11L);
        then(storeTransactionEligibilityService).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("메뉴 예약은 합산·ID 정렬된 실제 create command를 보내고 CONFIRMED 상세을 반환한다")
    void createsMenuHoldWithMergedStableMenuOrder() {
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22", SERVICE_DATE, START_TIME, null,
                new ReservationPartyRequest(2, 0, 0),
                List.of(
                        new ReservationMenuSelectionRequest("92", 2),
                        new ReservationMenuSelectionRequest("91", 1),
                        new ReservationMenuSelectionRequest("91", 2)));
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0),
                10, 3, 0, 0, 1, 6, true, 7L);
        ReflectionTestUtils.setField(bucket, "id", 301L);
        stubSuccessfulCreation(bucket, activePolicy(22L, 30, 60, 0));
        given(menuHoldPort.create(any(ReservationMenuHoldCreateCommand.class)))
                .willReturn(ReservationMenuHoldResult.confirmed(77L));
        given(menuHoldPort.findSnapshots(77L))
                .willReturn(List.of(
                        new ReservationMenuHoldItemSnapshot(91L, "아메리카노", 4_500L, 3),
                        new ReservationMenuHoldItemSnapshot(92L, "케이크", 7_000L, 2)));

        ReservationCreationCommandResult result = reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request);

        assertThat(result.data().menuSelections())
                .extracting(selection -> selection.menuId(), selection -> selection.quantity())
                .containsExactly(tuple("91", 3), tuple("92", 2));
        ArgumentCaptor<ReservationMenuHoldCreateCommand> command =
                ArgumentCaptor.forClass(ReservationMenuHoldCreateCommand.class);
        then(menuHoldPort).should().create(command.capture());
        assertThat(command.getValue().reservationId()).isEqualTo(77L);
        assertThat(command.getValue().operationId())
                .isEqualTo("reservation-create:77:550e8400-e29b-41d4-a716-446655440000");
        assertThat(command.getValue().menuSelections())
                .extracting(selection -> selection.menuId(), selection -> selection.quantity())
                .containsExactly(tuple(91L, 3), tuple(92L, 2));
    }

    @Test
    @DisplayName("같은 사용자·매장의 half-open 서비스 구간 중복은 수용량 잠금 전에 RESERVATION_004다")
    void rejectsConfirmedServiceOverlapBeforeCapacityLock() {
        ReservationCreateRequest request = creationRequest(2, 0, 0);
        stubCreationUntilCapacity(
                activePolicy(22L, 30, 60, 0),
                List.of(reservation(88L, 11L)),
                null);

        assertThatThrownBy(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.DUPLICATE_RESERVATION));

        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(reservationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("유효한 시간 window에서 점유 구간 버킷 coverage가 부족하면 RESERVATION_003이다")
    void rejectsIncompleteCapacityCoverage() {
        ReservationCapacityBucket partial = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(18, 30),
                10, 3, 0, 0, 1, 6, true, 7L);
        stubCreationUntilCapacity(
                activePolicy(22L, 30, 60, 0), List.of(), List.of(partial));

        assertThatThrownBy(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                creationRequest(2, 0, 0)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.INSUFFICIENT_CAPACITY));

        assertThat(partial.getOccupiedPeople()).isZero();
        assertThat(partial.getOccupiedTeams()).isZero();
        then(reservationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("latest overlapping buckets create a reservation when their clipped coverage is continuous")
    void createsReservationWhenLatestBucketsCoverClippedOccupancy() {
        ReservationCapacityBucket leading = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(17, 30), LocalTime.of(18, 30),
                10, 3, 1, 1, 1, 6, true, 7L);
        ReservationCapacityBucket trailing = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 30), LocalTime.of(19, 0),
                10, 3, 1, 1, 1, 6, true, 7L);
        ReflectionTestUtils.setField(leading, "id", 301L);
        ReflectionTestUtils.setField(trailing, "id", 302L);
        stubSuccessfulCreation(leading, activePolicy(22L, 30, 60, 0));
        given(capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0)))
                .willReturn(List.of(leading, trailing));

        ReservationCreationCommandResult result = reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                creationRequest(2, 0, 0));

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data().reservationId()).isEqualTo("77");
        assertThat(leading.getOccupiedPeople()).isEqualTo(3);
        assertThat(leading.getOccupiedTeams()).isEqualTo(2);
        assertThat(trailing.getOccupiedPeople()).isEqualTo(3);
        assertThat(trailing.getOccupiedTeams()).isEqualTo(2);
        then(capacityAllocationRepository).should().saveAll(any());
    }

    @Test
    @DisplayName("겹치는 버킷 coverage는 점유 전에 RESERVATION_003으로 거절한다")
    void rejectsOverlappingCapacityCoverageBeforeOccupancyMutation() {
        ReservationCapacityBucket first = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(18, 45),
                10, 3, 1, 1, 1, 6, true, 7L);
        ReservationCapacityBucket overlapping = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 30), LocalTime.of(19, 0),
                10, 3, 2, 1, 1, 6, true, 7L);
        stubCreationUntilCapacity(
                activePolicy(22L, 30, 60, 0),
                List.of(),
                List.of(first, overlapping));

        Throwable failure = catchThrowable(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                creationRequest(2, 0, 0)));

        assertThat(first.getOccupiedPeople()).isEqualTo(1);
        assertThat(first.getOccupiedTeams()).isEqualTo(1);
        assertThat(overlapping.getOccupiedPeople()).isEqualTo(2);
        assertThat(overlapping.getOccupiedTeams()).isEqualTo(1);
        assertThat(failure).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.INSUFFICIENT_CAPACITY));
        then(reservationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("coverage 완료 뒤 반환된 겹침 버킷도 점유 전에 RESERVATION_003으로 거절한다")
    void rejectsOverlappingCapacityBucketAfterCompleteCoverageBeforeOccupancyMutation() {
        ReservationCapacityBucket complete = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0),
                10, 3, 1, 1, 1, 6, true, 7L);
        ReservationCapacityBucket overlapping = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, LocalTime.of(18, 30), LocalTime.of(19, 0),
                10, 3, 2, 1, 1, 6, true, 7L);
        stubCreationUntilCapacity(
                activePolicy(22L, 30, 60, 0),
                List.of(),
                List.of(complete, overlapping));

        Throwable failure = catchThrowable(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                creationRequest(2, 0, 0)));

        assertThat(complete.getOccupiedPeople()).isEqualTo(1);
        assertThat(complete.getOccupiedTeams()).isEqualTo(1);
        assertThat(overlapping.getOccupiedPeople()).isEqualTo(2);
        assertThat(overlapping.getOccupiedTeams()).isEqualTo(1);
        assertThat(failure).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.INSUFFICIENT_CAPACITY));
        then(reservationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("버킷의 최소·최대 일행 범위를 벗어나면 점유 전 RESERVATION_009다")
    void rejectsPartyOutsideCapacityPolicyRange() {
        ReservationCapacityBucket restricted = ReservationCapacityBucket.create(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0),
                10, 3, 0, 0, 3, 6, true, 7L);
        stubCreationUntilCapacity(
                activePolicy(22L, 30, 60, 0), List.of(), List.of(restricted));

        assertThatThrownBy(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                creationRequest(2, 0, 0)))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.PARTY_SIZE_OUT_OF_RANGE));

        assertThat(restricted.getOccupiedPeople()).isZero();
        then(reservationRepository).should(never()).saveAndFlush(any());
    }

    @Test
    @DisplayName("명시적 startOffset으로 해소되고 같은 offset에 머무는 DST overlap 예약은 생성한다")
    void createsReservationInExplicitlyResolvedDstOverlap() {
        LocalDate serviceDate = LocalDate.of(2026, 11, 1);
        LocalTime startTime = LocalTime.of(1, 30);
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22", serviceDate, startTime, "-04:00",
                new ReservationPartyRequest(2, 0, 0), List.of());
        ReservationTimePolicyVersion policy = activePolicy(22L, 15, 15, 0);
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L, serviceDate, startTime, LocalTime.of(1, 45),
                10, 3, 0, 0, 1, 6, true, 7L);
        ReflectionTestUtils.setField(bucket, "id", 301L);

        given(consumerAccountService.getReservationContact(11L))
                .willReturn(new ReservationContactResult(
                        "consumer:11:channel:primary", true));
        AtomicReference<JsonNode> storedPayload = new AtomicReference<>();
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            JsonNode replayPayload = storedPayload.get();
            if (replayPayload != null) {
                return new IdempotentOutcome(
                        true, 201, "SUCCESS", "RESERVATION", "77", replayPayload);
            }
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            JsonNode createdPayload = new ObjectMapper().valueToTree(result.data());
            storedPayload.set(createdPayload);
            return new IdempotentOutcome(
                    false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(),
                    createdPayload);
        });
        given(storeTransactionEligibilityService
                .requireReservationTransactionEligibility(22L))
                .willReturn(new StoreReservationTransactionEligibility(
                        22L, "뉴욕 미리윰"));
        given(storeScheduleService.resolveReservationWindows(
                List.of(22L), serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        22L, "America/New_York",
                        LocalDateTime.of(serviceDate, LocalTime.of(1, 0)),
                        LocalDateTime.of(serviceDate, LocalTime.of(3, 0)))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                eq(java.util.Set.of(22L)),
                eq(ReservationTimePolicyStatus.ACTIVE),
                eq(ReservationTimePolicyStatus.SCHEDULED),
                eq(NOW))).willReturn(List.of(policy));
        given(storeServiceIntervalValidationService.validateServiceIntervals(any()))
                .willAnswer(invocation -> {
                    List<StoreServiceIntervalRequest> intervals = invocation.getArgument(0);
                    return List.of(StoreServiceIntervalResult.of(
                            intervals.getFirst(), true));
                });
        given(reservationRepository.findConfirmedOverlappingForUpdate(
                11L, 22L,
                Instant.parse("2026-11-01T05:30:00Z"),
                Instant.parse("2026-11-01T05:45:00Z")))
                .willReturn(List.of());
        given(capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                22L, serviceDate, startTime, LocalTime.of(1, 45)))
                .willReturn(List.of(bucket));
        given(cancellationPolicySelector.select())
                .willReturn(new ReservationCancellationPolicyVersion(1L));
        given(reservationRepository.saveAndFlush(any(Reservation.class)))
                .willAnswer(invocation -> {
                    Reservation saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 77L);
                    return saved;
                });
        ReservationCreationCommandResult created = reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request);
        ReservationCreationCommandResult replayed = reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request);

        assertThat(storedPayload.get().get("startAt").asString())
                .isEqualTo("2026-11-01T01:30:00-04:00");
        assertThat(storedPayload.get().get("serviceEndAt").asString())
                .isEqualTo("2026-11-01T01:45:00-04:00");
        assertThat(created.httpStatus()).isEqualTo(201);
        assertThat(created.data().startAt().getOffset()).isEqualTo(ZoneOffset.of("-04:00"));
        assertThat(created.data().serviceEndAt().getOffset()).isEqualTo(ZoneOffset.of("-04:00"));
        assertThat(replayed).isEqualTo(created);
        assertThat(bucket.getOccupiedPeople()).isEqualTo(2);
    }

    @Test
    @DisplayName("계산된 서비스 종료가 다른 UTC offset으로 넘어가면 수용량 변경 전 RESERVATION_002다")
    void rejectsCreationCrossingDstOffsetBoundaryBeforeCapacityMutation() {
        LocalDate serviceDate = LocalDate.of(2026, 11, 1);
        LocalTime startTime = LocalTime.of(1, 30);
        ReservationCreateRequest request = new ReservationCreateRequest(
                "22", serviceDate, startTime, "-04:00",
                new ReservationPartyRequest(2, 0, 0), List.of());
        given(consumerAccountService.getReservationContact(11L))
                .willReturn(new ReservationContactResult(
                        "consumer:11:channel:primary", true));
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            return work.get();
        });
        given(storeTransactionEligibilityService
                .requireReservationTransactionEligibility(22L))
                .willReturn(new StoreReservationTransactionEligibility(
                        22L, "뉴욕 미리윰"));
        given(storeScheduleService.resolveReservationWindows(
                List.of(22L), serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        22L, "America/New_York",
                        LocalDateTime.of(serviceDate, LocalTime.of(1, 0)),
                        LocalDateTime.of(serviceDate, LocalTime.of(4, 0)))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                eq(java.util.Set.of(22L)),
                eq(ReservationTimePolicyStatus.ACTIVE),
                eq(ReservationTimePolicyStatus.SCHEDULED),
                eq(NOW))).willReturn(List.of(activePolicy(22L, 30, 120, 0)));

        assertThatThrownBy(() -> reservationService.createReservation(
                11L,
                IdempotencyKey.parse("550e8400-e29b-41d4-a716-446655440000"),
                request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.OUTSIDE_RESERVATION_WINDOW));

        then(storeServiceIntervalValidationService).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    private ReservationCreateRequest creationRequest(
            int adultCount,
            int childCount,
            int infantCount
    ) {
        return new ReservationCreateRequest(
                "22", SERVICE_DATE, START_TIME, null,
                new ReservationPartyRequest(adultCount, childCount, infantCount),
                List.of());
    }

    private void stubCreationUntilCapacity(
            ReservationTimePolicyVersion policy,
            List<Reservation> overlaps,
            List<ReservationCapacityBucket> buckets
    ) {
        given(consumerAccountService.getReservationContact(11L))
                .willReturn(new ReservationContactResult(
                        "consumer:11:channel:primary", true));
        given(idempotencyExecutor.execute(any(), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            return work.get();
        });
        given(storeTransactionEligibilityService
                .requireReservationTransactionEligibility(22L))
                .willReturn(new StoreReservationTransactionEligibility(
                        22L, "미리윰 식당"));
        given(storeScheduleService.resolveReservationWindows(
                List.of(22L), SERVICE_DATE, START_TIME))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        22L, "Asia/Seoul",
                        LocalDateTime.of(SERVICE_DATE, LocalTime.of(17, 0)),
                        LocalDateTime.of(SERVICE_DATE, LocalTime.of(20, 0)))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                eq(java.util.Set.of(22L)),
                eq(ReservationTimePolicyStatus.ACTIVE),
                eq(ReservationTimePolicyStatus.SCHEDULED),
                eq(NOW))).willReturn(List.of(policy));
        given(storeServiceIntervalValidationService.validateServiceIntervals(any()))
                .willAnswer(invocation -> {
                    List<StoreServiceIntervalRequest> requests = invocation.getArgument(0);
                    return requests.stream()
                            .map(interval -> StoreServiceIntervalResult.of(interval, true))
                            .toList();
                });
        given(reservationRepository.findConfirmedOverlappingForUpdate(
                11L, 22L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(overlaps);
        if (overlaps.isEmpty()) {
            given(capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                    22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0)))
                    .willReturn(buckets);
        }
    }

    private void stubSuccessfulCreation(
            ReservationCapacityBucket bucket,
            ReservationTimePolicyVersion policy
    ) {
        lenient().when(consumerAccountService.getReservationContact(11L))
                .thenReturn(new ReservationContactResult(
                        "consumer:11:channel:primary", true));
        lenient().when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(
                    false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(),
                    new ObjectMapper().valueToTree(result.data()));
        });
        given(storeTransactionEligibilityService
                .requireReservationTransactionEligibility(22L))
                .willReturn(new StoreReservationTransactionEligibility(
                        22L, "미리윰 식당"));
        given(storeScheduleService.resolveReservationWindows(
                List.of(22L), SERVICE_DATE, START_TIME))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        22L, "Asia/Seoul",
                        LocalDateTime.of(SERVICE_DATE, LocalTime.of(17, 0)),
                        LocalDateTime.of(SERVICE_DATE, LocalTime.of(20, 0)))));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                eq(java.util.Set.of(22L)),
                eq(ReservationTimePolicyStatus.ACTIVE),
                eq(ReservationTimePolicyStatus.SCHEDULED),
                eq(NOW))).willReturn(List.of(policy));
        given(storeServiceIntervalValidationService.validateServiceIntervals(any()))
                .willAnswer(invocation -> {
                    List<StoreServiceIntervalRequest> requests = invocation.getArgument(0);
                    return requests.stream()
                            .map(interval -> StoreServiceIntervalResult.of(interval, true))
                            .toList();
                });
        given(reservationRepository.findConfirmedOverlappingForUpdate(
                11L, 22L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")))
                .willReturn(List.of());
        given(capacityBucketRepository.findLatestPolicyBucketsOverlappingForUpdate(
                22L, SERVICE_DATE, START_TIME, LocalTime.of(19, 0)))
                .willReturn(List.of(bucket));
        given(cancellationPolicySelector.select())
                .willReturn(new ReservationCancellationPolicyVersion(1L));
        given(reservationRepository.saveAndFlush(any(Reservation.class)))
                .willAnswer(invocation -> {
                    Reservation saved = invocation.getArgument(0);
                    ReflectionTestUtils.setField(saved, "id", 77L);
                    return saved;
                });
    }

    @Test
    @DisplayName("입력 순서와 중복을 보존하며 accepting 매장별 정책으로 서로 다른 점유 종료를 계산한다")
    void resolvesPerStoreTimesInInputOrderWithOnePolicyQuery() {
        // given
        List<Long> storeIds = List.of(2L, 1L, 3L, 2L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(
                StoreReservationWindowResult.accepting(
                        2L,
                        "Asia/Seoul",
                        requestedAt.minusHours(1),
                        requestedAt.plusMinutes(30)
                ),
                StoreReservationWindowResult.accepting(
                        1L,
                        "Asia/Seoul",
                        requestedAt,
                        requestedAt.plusMinutes(30)
                ),
                StoreReservationWindowResult.notAccepting(3L),
                StoreReservationWindowResult.accepting(
                        2L,
                        "Asia/Seoul",
                        requestedAt.minusHours(1),
                        requestedAt.plusMinutes(30)
                )
        ));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(
                activePolicy(1L, 30, 90, 30),
                activePolicy(2L, 30, 60, 15)
        ));
        StoreServiceIntervalRequest store2Interval = new StoreServiceIntervalRequest(
                2L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")
        );
        StoreServiceIntervalRequest store1Interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:30:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(store2Interval, store1Interval, store2Interval)
        )).willReturn(List.of(
                StoreServiceIntervalResult.of(store2Interval, true),
                StoreServiceIntervalResult.of(store1Interval, false),
                StoreServiceIntervalResult.of(store2Interval, true)
        ));

        // when
        List<ReservationTimeResolutionResult> results = timeResolutionService
                .resolveReservationTimes(
                        storeIds,
                        new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
                );

        // then
        assertThat(results).extracting(ReservationTimeResolutionResult::storeId)
                .containsExactly(2L, 1L, 3L, 2L);
        assertThat(results).extracting(ReservationTimeResolutionResult::status)
                .containsExactly(
                        ReservationTimeResolutionStatus.RESOLVED,
                        ReservationTimeResolutionStatus.UNAVAILABLE,
                        ReservationTimeResolutionStatus.UNAVAILABLE,
                        ReservationTimeResolutionStatus.RESOLVED
                );
        assertThat(results.get(0).time().occupancyEndAt())
                .isEqualTo(Instant.parse("2026-08-03T10:15:00Z"));
        assertThat(results.get(1).time()).isNull();
        assertThat(results.get(2).time()).isNull();
        assertThat(results.get(3).time().occupancyEndAt())
                .isEqualTo(results.get(0).time().occupancyEndAt());

        @SuppressWarnings("unchecked")
        ArgumentCaptor<Collection<Long>> idsCaptor = ArgumentCaptor.forClass(Collection.class);
        then(timePolicyRepository).should(times(1)).findResolutionCandidatesByStoreIds(
                idsCaptor.capture(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        );
        assertThat(idsCaptor.getValue()).containsExactlyInAnyOrder(1L, 2L);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<StoreServiceIntervalRequest>> intervalCaptor =
                ArgumentCaptor.forClass(List.class);
        then(storeServiceIntervalValidationService).should(times(1))
                .validateServiceIntervals(intervalCaptor.capture());
        assertThat(intervalCaptor.getValue()).containsExactly(
                store2Interval,
                store1Interval,
                store2Interval
        );
        assertThat(intervalCaptor.getValue().getFirst().serviceEndAt())
                .isNotEqualTo(results.getFirst().time().occupancyEndAt());
    }

    @Test
    @DisplayName("모든 매장이 시작 시각을 접수하지 않으면 시간 정책 repository를 조회하지 않는다")
    void skipsPolicyRepositoryWhenAllStoresAreNotAccepting() {
        List<Long> storeIds = List.of(3L, 3L, 4L);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(
                StoreReservationWindowResult.notAccepting(3L),
                StoreReservationWindowResult.notAccepting(3L),
                StoreReservationWindowResult.notAccepting(4L)
        ));

        List<ReservationTimeResolutionResult> results = timeResolutionService
                .resolveReservationTimes(
                        storeIds,
                        new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
                );

        assertThat(results).extracting(ReservationTimeResolutionResult::storeId)
                .containsExactly(3L, 3L, 4L);
        assertThat(results).extracting(ReservationTimeResolutionResult::status)
                .containsOnly(ReservationTimeResolutionStatus.UNAVAILABLE);
        then(timePolicyRepository).should(never()).findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.any()
        );
        then(storeServiceIntervalValidationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("슬롯 정렬은 자정이 아니라 Store가 반환한 windowStartAt을 기준으로 판정한다")
    void rejectsStartMisalignedFromWindowStart() {
        List<Long> storeIds = List.of(1L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                1L,
                "Asia/Seoul",
                requestedAt.minusMinutes(50),
                requestedAt.plusHours(2)
        )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 0)));
        List<ReservationTimeResolutionResult> results = timeResolutionService
                .resolveReservationTimes(
                        storeIds,
                        new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
                );

        assertThat(results).singleElement().satisfies(result -> {
            assertThat(result.status()).isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
            assertThat(result.time()).isNull();
        });
        then(storeServiceIntervalValidationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("DST 중복 시각은 offset 없이는 실패 폐쇄하고 유효 offset이 있으면 계산한다")
    void requiresExplicitOffsetForAmbiguousDstStart() {
        LocalDate serviceDate = LocalDate.of(2026, 10, 25);
        LocalTime startTime = LocalTime.of(2, 30);
        LocalDateTime requestedAt = LocalDateTime.of(serviceDate, startTime);
        List<Long> storeIds = List.of(1L);
        given(storeScheduleService.resolveReservationWindows(storeIds, serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        1L,
                        "Europe/Paris",
                        requestedAt.minusMinutes(30),
                        requestedAt.plusMinutes(30)
                )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 0)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-10-25T00:30:00Z"),
                Instant.parse("2026-10-25T01:30:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(interval)
        )).willReturn(List.of(StoreServiceIntervalResult.of(interval, true)));

        ReservationTimeResolutionResult ambiguous = timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(serviceDate, startTime, null)
        ).getFirst();
        ReservationTimeResolutionResult explicit = timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(serviceDate, startTime, ZoneOffset.ofHours(2))
        ).getFirst();

        assertThat(ambiguous.status()).isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
        assertThat(explicit.status()).isEqualTo(ReservationTimeResolutionStatus.RESOLVED);
        assertThat(explicit.time().startAt())
                .isEqualTo(Instant.parse("2026-10-25T00:30:00Z"));
        then(timePolicyRepository).should(times(2)).findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        );
        then(storeServiceIntervalValidationService).should(times(1))
                .validateServiceIntervals(List.of(interval));
    }

    @Test
    @DisplayName("정책 원본이 없으면 해당 매장만 실패 폐쇄한다")
    void failsClosedWhenTimePolicyIsMissing() {
        List<Long> storeIds = List.of(1L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                1L,
                "Asia/Seoul",
                requestedAt,
                requestedAt.plusHours(2)
        )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of());

        assertThat(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).singleElement().satisfies(result ->
                assertThat(result.status())
                        .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE));
        then(storeServiceIntervalValidationService).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("Store 서비스 구간 응답이 계약과 다르면 입력 전체를 실패 폐쇄한다")
    void failsClosedWhenStoreServiceIntervalResponseIsMalformed() {
        List<Long> storeIds = List.of(1L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                1L,
                "Asia/Seoul",
                requestedAt,
                requestedAt.plusHours(2)
        )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 15)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-08-03T09:00:00Z"),
                Instant.parse("2026-08-03T10:00:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(interval)
        )).willReturn(
                null,
                List.of(),
                List.of(new StoreServiceIntervalResult(
                        2L,
                        interval.startAt(),
                        interval.serviceEndAt(),
                        com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalStatus.ACCEPTING
                )),
                List.of(new StoreServiceIntervalResult(
                        interval.storeId(),
                        interval.startAt(),
                        interval.serviceEndAt(),
                        null
                ))
        );

        for (int attempt = 0; attempt < 4; attempt++) {
            assertThat(timeResolutionService.resolveReservationTimes(
                    storeIds,
                    new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
            )).singleElement().satisfies(result -> {
                assertThat(result.status())
                        .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
                assertThat(result.time()).isNull();
            });
        }
        then(storeServiceIntervalValidationService).should(times(4))
                .validateServiceIntervals(List.of(interval));
    }

    @Test
    @DisplayName("효력 시각이 지났지만 아직 전환되지 않은 게시 예약이 있으면 기존 ACTIVE를 사용하지 않는다")
    void failsClosedWhileDueScheduledPolicyIsPendingActivation() {
        List<Long> storeIds = List.of(1L);
        LocalDateTime requestedAt = LocalDateTime.of(SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(StoreReservationWindowResult.accepting(
                1L,
                "Asia/Seoul",
                requestedAt,
                requestedAt.plusHours(2)
        )));
        ReservationTimePolicyVersion due = ReservationTimePolicyVersion.createDraft(
                1L,
                2L,
                30,
                120,
                0
        );
        due.schedule(NOW, NOW.minusSeconds(60), "효력 도달 정책");
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(
                activePolicy(1L, 30, 60, 0),
                due
        ));

        assertThat(timeResolutionService.resolveReservationTimes(
                storeIds,
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).singleElement().satisfies(result -> {
            assertThat(result.status())
                    .isEqualTo(ReservationTimeResolutionStatus.UNAVAILABLE);
            assertThat(result.time()).isNull();
        });
    }

    @Test
    @DisplayName("빈 매장 입력은 Store와 정책 repository를 조회하지 않고 빈 결과를 반환한다")
    void returnsEmptyWithoutDependenciesForEmptyStoreInput() {
        assertThat(timeResolutionService.resolveReservationTimes(
                List.of(),
                new ReservationTimeRequest(SERVICE_DATE, START_TIME, null)
        )).isEmpty();

        then(storeScheduleService).shouldHaveNoInteractions();
        then(timePolicyRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("매장별로 계산한 서로 다른 점유 종료 시각으로 수용량을 한 번에 판정한다")
    void getAvailabilitiesUsesPerStoreResolvedOccupancyEnds() {
        List<Long> storeIds = List.of(1L, 2L);
        LocalDateTime requestedAt = LocalDateTime.of(CAPACITY_SERVICE_DATE, START_TIME);
        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                CAPACITY_SERVICE_DATE,
                START_TIME
        )).willReturn(List.of(
                StoreReservationWindowResult.accepting(
                        1L,
                        "Asia/Seoul",
                        requestedAt,
                        requestedAt.plusHours(2)
                ),
                StoreReservationWindowResult.accepting(
                        2L,
                        "Asia/Seoul",
                        requestedAt,
                        requestedAt.plusHours(2)
                )
        ));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(
                activePolicy(1L, 30, 60, 0),
                activePolicy(2L, 30, 90, 0)
        ));
        StoreServiceIntervalRequest store1Interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-08-02T09:00:00Z"),
                Instant.parse("2026-08-02T10:00:00Z")
        );
        StoreServiceIntervalRequest store2Interval = new StoreServiceIntervalRequest(
                2L,
                Instant.parse("2026-08-02T09:00:00Z"),
                Instant.parse("2026-08-02T10:30:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(store1Interval, store2Interval)
        )).willReturn(List.of(
                StoreServiceIntervalResult.of(store1Interval, true),
                StoreServiceIntervalResult.of(store2Interval, true)
        ));
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                storeIds,
                CAPACITY_SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 30)
        )).willReturn(List.of(
                capacityBucket(1L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(1L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0),
                capacityBucket(1L, LocalTime.of(19, 0), LocalTime.of(19, 30), 0, 0),
                capacityBucket(2L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(2L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0),
                capacityBucket(2L, LocalTime.of(19, 0), LocalTime.of(19, 30), 0, 0)
        ));

        List<ReservationAvailabilityResult> results = reservationService.getAvailabilities(
                storeIds,
                new ReservationAvailabilityCondition(
                        CAPACITY_SERVICE_DATE,
                        START_TIME,
                        null,
                        2,
                        false
                )
        );

        assertThat(results).containsExactly(
                new ReservationAvailabilityResult(
                        1L,
                        ReservationAvailabilityStatus.AVAILABLE
                ),
                new ReservationAvailabilityResult(
                        2L,
                        ReservationAvailabilityStatus.AVAILABLE
                )
        );
        then(capacityBucketRepository).should(times(1))
                .findLatestPolicyBucketsOverlapping(
                        storeIds,
                        CAPACITY_SERVICE_DATE,
                        START_TIME,
                        LocalTime.of(19, 30)
                );
    }

    @Test
    @DisplayName("DST 중복 구간은 로컬 수용량 버킷으로 추측하지 않고 실패 폐쇄한다")
    void getAvailabilityFailsClosedForAmbiguousDstCapacityWindow() {
        LocalDate serviceDate = LocalDate.of(2026, 10, 25);
        LocalTime startTime = LocalTime.of(2, 30);
        LocalDateTime requestedAt = LocalDateTime.of(serviceDate, startTime);
        List<Long> storeIds = List.of(1L);
        given(storeScheduleService.resolveReservationWindows(storeIds, serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        1L,
                        "Europe/Paris",
                        requestedAt.minusMinutes(30),
                        requestedAt.plusMinutes(30)
                )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 60, 0)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-10-25T00:30:00Z"),
                Instant.parse("2026-10-25T01:30:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(interval)
        )).willReturn(List.of(StoreServiceIntervalResult.of(interval, true)));

        ReservationAvailabilityResult result = reservationService.getAvailability(
                1L,
                new ReservationAvailabilityCondition(
                        serviceDate,
                        startTime,
                        ZoneOffset.ofHours(2),
                        2,
                        false
                )
        );

        assertThat(result).isEqualTo(new ReservationAvailabilityResult(
                1L,
                ReservationAvailabilityStatus.UNAVAILABLE
        ));
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("자정을 넘는 점유 구간은 로컬 수용량 버킷으로 추측하지 않고 실패 폐쇄한다")
    void getAvailabilityFailsClosedForCrossMidnightCapacityWindow() {
        LocalDate serviceDate = LocalDate.of(2026, 8, 2);
        LocalTime startTime = LocalTime.of(23, 30);
        LocalDateTime requestedAt = LocalDateTime.of(serviceDate, startTime);
        List<Long> storeIds = List.of(1L);
        given(storeScheduleService.resolveReservationWindows(storeIds, serviceDate, startTime))
                .willReturn(List.of(StoreReservationWindowResult.accepting(
                        1L,
                        "Asia/Seoul",
                        requestedAt.minusMinutes(30),
                        requestedAt.plusMinutes(30)
                )));
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(List.of(activePolicy(1L, 30, 90, 0)));
        StoreServiceIntervalRequest interval = new StoreServiceIntervalRequest(
                1L,
                Instant.parse("2026-08-02T14:30:00Z"),
                Instant.parse("2026-08-02T16:00:00Z")
        );
        given(storeServiceIntervalValidationService.validateServiceIntervals(
                List.of(interval)
        )).willReturn(List.of(StoreServiceIntervalResult.of(interval, true)));

        ReservationAvailabilityResult result = reservationService.getAvailability(
                1L,
                new ReservationAvailabilityCondition(
                        serviceDate,
                        startTime,
                        null,
                        2,
                        false
                )
        );

        assertThat(result).isEqualTo(new ReservationAvailabilityResult(
                1L,
                ReservationAvailabilityStatus.UNAVAILABLE
        ));
        then(capacityBucketRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("요청 구간을 연속으로 덮는 모든 버킷에 여유가 있으면 예약 가능하다")
    void getAvailabilityReturnsAvailableWhenEveryContiguousBucketCanAccept() {
        ReservationAvailabilityCondition condition = capacityCondition(4, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 6, 2),
                capacityBucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 4, 1)
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result).isEqualTo(new ReservationAvailabilityResult(
                22L,
                ReservationAvailabilityStatus.AVAILABLE
        ));
    }

    @Test
    @DisplayName("필요한 구간 사이에 버킷 공백이 있으면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenCoverageHasGap() {
        ReservationAvailabilityCondition condition = capacityCondition(2, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(22L, LocalTime.of(18, 45), LocalTime.of(19, 0), 0, 0)
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("필요 구간에서 버킷이 겹치면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenCoverageOverlaps() {
        ReservationAvailabilityCondition condition = capacityCondition(2, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 45), 0, 0),
                capacityBucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0)
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("필요 구간에 서로 다른 정책 버전이 섞이면 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenPolicyVersionsAreMixed() {
        ReservationAvailabilityCondition condition = capacityCondition(2, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(
                        22L,
                        LocalTime.of(18, 0),
                        LocalTime.of(18, 30),
                        0,
                        0,
                        3L
                ),
                capacityBucket(
                        22L,
                        LocalTime.of(18, 30),
                        LocalTime.of(19, 0),
                        0,
                        0,
                        4L
                )
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("겹치는 버킷 중 하나라도 수용량이 부족하면 전체 예약 불가다")
    void getAvailabilityReturnsUnavailableWhenAnyBucketIsInsufficient() {
        ReservationAvailabilityCondition condition = capacityCondition(4, false);
        givenResolvedCapacityTimes(List.of(22L), CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                List.of(22L),
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(22L, LocalTime.of(18, 0), LocalTime.of(18, 30), 6, 2),
                capacityBucket(22L, LocalTime.of(18, 30), LocalTime.of(19, 0), 8, 1)
        ));

        ReservationAvailabilityResult result =
                reservationService.getAvailability(22L, condition);

        assertThat(result.availability())
                .isEqualTo(ReservationAvailabilityStatus.UNAVAILABLE);
    }

    @Test
    @DisplayName("일괄 판정은 저장소를 한 번 조회하고 입력 매장의 순서와 중복을 보존한다")
    void getAvailabilitiesUsesOneQueryAndPreservesInputOrderAndDuplicates() {
        ReservationAvailabilityCondition condition = capacityCondition(2, false);
        List<Long> storeIds = List.of(30L, 10L, 20L, 30L);
        List<Long> queriedStoreIds = List.of(30L, 10L, 20L);
        givenResolvedCapacityTimes(storeIds, CAPACITY_END_TIME);
        given(capacityBucketRepository.findLatestPolicyBucketsOverlapping(
                queriedStoreIds,
                CAPACITY_SERVICE_DATE,
                START_TIME,
                CAPACITY_END_TIME
        )).willReturn(List.of(
                capacityBucket(20L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0),
                capacityBucket(30L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(20L, LocalTime.of(18, 0), LocalTime.of(18, 30), 0, 0),
                capacityBucket(30L, LocalTime.of(18, 30), LocalTime.of(19, 0), 0, 0)
        ));

        List<ReservationAvailabilityResult> results =
                reservationService.getAvailabilities(storeIds, condition);

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
                ),
                new ReservationAvailabilityResult(
                        30L,
                        ReservationAvailabilityStatus.AVAILABLE
                )
        );
        then(capacityBucketRepository).should(times(1))
                .findLatestPolicyBucketsOverlapping(
                        queriedStoreIds,
                        CAPACITY_SERVICE_DATE,
                        START_TIME,
                        CAPACITY_END_TIME
                );
    }

    @Test
    @DisplayName("빈 매장 후보는 저장소를 조회하지 않고 빈 결과를 반환한다")
    void getAvailabilitiesReturnsEmptyWithoutRepositoryQuery() {
        List<ReservationAvailabilityResult> results =
                reservationService.getAvailabilities(
                        List.of(),
                        capacityCondition(2, false)
                );

        assertThat(results).isEmpty();
        then(capacityBucketRepository).should(never())
                .findLatestPolicyBucketsOverlapping(
                        List.of(),
                        CAPACITY_SERVICE_DATE,
                        START_TIME,
                        CAPACITY_END_TIME
                );
    }

    private void givenResolvedCapacityTimes(
            List<Long> storeIds,
            LocalTime occupancyEndTime
    ) {
        LocalDateTime requestedAt =
                LocalDateTime.of(CAPACITY_SERVICE_DATE, START_TIME);
        int durationMinutes = Math.toIntExact(
                Duration.between(START_TIME, occupancyEndTime).toMinutes()
        );
        List<StoreReservationWindowResult> windows = storeIds.stream()
                .map(storeId -> StoreReservationWindowResult.accepting(
                        storeId,
                        "Asia/Seoul",
                        requestedAt,
                        requestedAt.plusHours(2)
                ))
                .toList();
        List<ReservationTimePolicyVersion> policies = storeIds.stream()
                .distinct()
                .map(storeId -> activePolicy(storeId, 30, durationMinutes, 0))
                .toList();
        Instant startAt = requestedAt.toInstant(ZoneOffset.ofHours(9));
        List<StoreServiceIntervalRequest> intervals = storeIds.stream()
                .map(storeId -> new StoreServiceIntervalRequest(
                        storeId,
                        startAt,
                        startAt.plus(Duration.ofMinutes(durationMinutes))
                ))
                .toList();

        given(storeScheduleService.resolveReservationWindows(
                storeIds,
                CAPACITY_SERVICE_DATE,
                START_TIME
        )).willReturn(windows);
        given(timePolicyRepository.findResolutionCandidatesByStoreIds(
                org.mockito.ArgumentMatchers.anyCollection(),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.ACTIVE),
                org.mockito.ArgumentMatchers.eq(ReservationTimePolicyStatus.SCHEDULED),
                org.mockito.ArgumentMatchers.eq(NOW)
        )).willReturn(policies);
        given(storeServiceIntervalValidationService.validateServiceIntervals(intervals))
                .willReturn(intervals.stream()
                        .map(interval -> StoreServiceIntervalResult.of(interval, true))
                        .toList());
    }

    private static ReservationAvailabilityCondition capacityCondition(
            int partySize,
            boolean includesInfants
    ) {
        return new ReservationAvailabilityCondition(
                CAPACITY_SERVICE_DATE,
                START_TIME,
                null,
                partySize,
                includesInfants
        );
    }

    private static ReservationCapacityBucket capacityBucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            int occupiedPeople,
            int occupiedTeams
    ) {
        return capacityBucket(
                storeId,
                startTime,
                endTime,
                occupiedPeople,
                occupiedTeams,
                3L
        );
    }

    private static ReservationCapacityBucket capacityBucket(
            long storeId,
            LocalTime startTime,
            LocalTime endTime,
            int occupiedPeople,
            int occupiedTeams,
            long policyVersion
    ) {
        return ReservationCapacityBucket.create(
                storeId,
                CAPACITY_SERVICE_DATE,
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

    private static ReservationTimePolicyVersion activePolicy(
            long storeId,
            int slotIntervalMinutes,
            int serviceDurationMinutes,
            int turnoverDurationMinutes
    ) {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                storeId,
                1L,
                slotIntervalMinutes,
                serviceDurationMinutes,
                turnoverDurationMinutes
        );
        policy.activate(NOW.minusSeconds(1), "활성 정책");
        return policy;
    }

    @Test
    @DisplayName("소비자 활성 gate와 멱등 선점 뒤 actor 범위 잠금으로 타인 예약을 숨긴다")
    void cancellationGateConsumerRunsBeforeIdempotencyAndHidesForeignReservation() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        stubFreshIdempotency(command, null);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.empty());

        assertThatThrownBy(() -> invokeConsumerCancellation(
                command, null, NOW.minusSeconds(10), CONSUMER_CANCELLATION_CORRELATION))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));

        InOrder order = inOrder(
                consumerAccountService,
                idempotencyExecutor,
                reservationRepository
        );
        order.verify(consumerAccountService).requireActiveAccount(11L);
        order.verify(idempotencyExecutor).execute(eq(command), any());
        order.verify(reservationRepository)
                .findByIdAndConsumerAccountIdForUpdate(77L, 11L);
        then(reservationRepository).should(never()).findById(77L);
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        then(cancellationAuditRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("알림 publisher가 없는 부분 배선 서비스는 예약 취소 변경 전에 명시적으로 실패한다")
    void cancellationRequiresNotificationPublisherBeforeMutation() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        Reservation reservation = confirmedReservation();
        stubFreshIdempotency(command, null);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(reservation));
        ReflectionTestUtils.setField(reservationService, "notificationPublisher", null);

        assertThatThrownBy(() -> invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        ))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("reservation cancellation dependencies are required");

        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        then(cancellationPolicyEvaluator).shouldHaveNoInteractions();
        then(capacityAllocationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("최신 정책에 겹치는 버킷이 없으면 원본 점유만 복구해 취소한다")
    void cancellationAllowsEmptyCurrentBucketsForNewerPolicy() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        CancellationFixture fixture = stubSuccessfulCancellation(
                command, ReservationMenuHoldTerminationPresence.NO_HOLD);
        ReservationCapacityBucket firstOriginal = fixture.lockedBuckets().get(0);
        ReservationCapacityBucket secondOriginal = fixture.lockedBuckets().get(1);

        reset(capacityBucketRepository);
        given(capacityBucketRepository.findLatestPolicyVersion(22L, SERVICE_DATE))
                .willReturn(Optional.of(4L));
        given(capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
                List.of(22L), SERVICE_DATE, START_TIME, LocalTime.of(19, 15)))
                .willReturn(List.of());
        given(capacityBucketRepository.findAllByIdInForUpdate(List.of(301L, 302L)))
                .willReturn(List.of(firstOriginal, secondOriginal));

        Throwable failure = catchThrowable(() -> invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        ));

        assertThat(failure).isNull();
        assertThat(fixture.reservation().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(List.of(firstOriginal, secondOriginal)).allSatisfy(bucket -> {
            assertThat(bucket.getOccupiedPeople()).isEqualTo(3);
            assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
        });
    }

    @Test
    @DisplayName("최신 정책이 예약 구간 일부만 덮으면 실제 겹치는 점유도 함께 복구한다")
    void cancellationAllowsPartialCurrentCoverageForNewerPolicy() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        CancellationFixture fixture = stubSuccessfulCancellation(
                command, ReservationMenuHoldTerminationPresence.NO_HOLD);
        ReservationCapacityBucket firstOriginal = fixture.lockedBuckets().get(0);
        ReservationCapacityBucket secondOriginal = fixture.lockedBuckets().get(1);
        ReservationCapacityBucket current = cancellationBucket(
                401L, LocalTime.of(18, 15), LocalTime.of(18, 45), 6, 2, 4L);

        given(capacityBucketRepository.findAllByIdInForUpdate(
                List.of(301L, 302L, 401L)))
                .willReturn(List.of(firstOriginal, secondOriginal, current));

        Throwable failure = catchThrowable(() -> invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        ));

        assertThat(failure).isNull();
        assertThat(fixture.reservation().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(List.of(firstOriginal, secondOriginal, current)).allSatisfy(bucket -> {
            assertThat(bucket.getOccupiedPeople()).isEqualTo(3);
            assertThat(bucket.getOccupiedTeams()).isEqualTo(1);
        });
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("소비자 취소의 0·음수 예약 ID도 actor 범위 조회로 RESERVATION_001을 반환한다")
    void cancellationConsumerNonPositiveReservationIdUsesActorScopedLookup(
            long reservationId
    ) {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        stubFreshIdempotency(command, null);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(
                reservationId, 11L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> invokeConsumerCancellation(
                reservationId,
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));

        then(reservationRepository).should()
                .findByIdAndConsumerAccountIdForUpdate(reservationId, 11L);
        then(reservationRepository).should(never()).findById(reservationId);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    @DisplayName("운영자 취소의 0·음수 예약 ID도 actor 범위 조회로 RESERVATION_001을 반환한다")
    void cancellationStoreNonPositiveReservationIdUsesActorScopedLookup(
            long reservationId
    ) {
        IdempotencyCommand command = cancellationCommand("store-operator", 33L);
        stubFreshIdempotency(command, null);
        given(reservationRepository.findByIdAndStoreIdForUpdate(
                reservationId, 22L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> invokeStoreCancellation(
                reservationId,
                command,
                "운영자 취소",
                NOW.minusSeconds(10),
                OPERATOR_CANCELLATION_CORRELATION
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));

        then(reservationRepository).should()
                .findByIdAndStoreIdForUpdate(reservationId, 22L);
        then(reservationRepository).should(never()).findById(reservationId);
    }

    @Test
    @DisplayName("운영자 관리권한 gate 실패는 멱등과 예약 접근보다 먼저 종료한다")
    void cancellationGateOperatorOwnershipRunsBeforeIdempotency() {
        IdempotencyCommand command = cancellationCommand("store-operator", 33L);
        willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED))
                .given(storeService)
                .requireManagementOwnership(33L, 22L);

        assertThatThrownBy(() -> invokeStoreCancellation(
                command,
                "운영자 취소",
                NOW.minusSeconds(10),
                OPERATOR_CANCELLATION_CORRELATION
        )).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode()).isEqualTo(StoreErrorCode.ACCESS_DENIED));

        then(idempotencyExecutor).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        then(cancellationAuditRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("취소 replay는 저장 detail만 복원하고 도메인 자원을 다시 읽지 않는다")
    void cancellationReplayUsesStoredDataWithoutDomainReads() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        Reservation cancelled = reservation(77L, 11L);
        cancelled.cancel(NOW.minusSeconds(1));
        ReservationDetailResponse stored = ReservationDetailResponse.from(
                cancelled,
                List.of(),
                ReservationCancellationActorType.CONSUMER,
                "사용자 사유"
        );
        given(idempotencyExecutor.execute(eq(command), any())).willReturn(
                new IdempotentOutcome(
                        true,
                        200,
                        "SUCCESS",
                        "RESERVATION",
                        "77",
                        new ObjectMapper().valueToTree(stored)
                )
        );

        Object result = invokeConsumerCancellation(
                command,
                "사용자 사유",
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );

        assertThat(cancellationHttpStatus(result)).isEqualTo(200);
        assertThat(cancellationData(result)).isEqualTo(stored);
        then(consumerAccountService).should().requireActiveAccount(11L);
        then(reservationRepository).shouldHaveNoInteractions();
        then(capacityAllocationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        then(cancellationAuditRepository).shouldHaveNoInteractions();
        then(cancellationPolicyEvaluator).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("fresh 취소는 상태를 evaluator보다 먼저 검사하고 RESERVATION_005로 거절한다")
    void cancellationPolicyRejectsStateBeforeEvaluator() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        Reservation cancelled = reservation(77L, 11L);
        cancelled.cancel(NOW.minusSeconds(1));
        stubFreshIdempotency(command, null);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(cancelled));

        assertThatThrownBy(() -> invokeConsumerCancellation(
                command, null, NOW.minusSeconds(10), CONSUMER_CANCELLATION_CORRELATION))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.INVALID_STATE_TRANSITION));

        then(cancellationPolicyEvaluator).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(cancellationAuditRepository).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"version", "startAt"})
    @DisplayName("null 저장 정책 버전과 legacy null startAt은 evaluator 전에 RESERVATION_006이다")
    void cancellationPolicyRejectsNullVersionAndNullStartBeforeEvaluator(String missingField) {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        Reservation reservation = reservation(77L, 11L);
        if ("version".equals(missingField)) {
            ReflectionTestUtils.setField(reservation, "cancellationPolicyVersion", null);
        } else {
            ReflectionTestUtils.setField(reservation.getTimeSnapshot(), "startAt", null);
        }
        stubFreshIdempotency(command, null);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(reservation));

        assertThatThrownBy(() -> invokeConsumerCancellation(
                command, null, NOW.minusSeconds(10), CONSUMER_CANCELLATION_CORRELATION))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.CANCELLATION_NOT_ALLOWED));

        then(cancellationPolicyEvaluator).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(cancellationAuditRepository).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @MethodSource("cancellationPolicyArgumentCases")
    @DisplayName("저장 version·actor·status·startAt·requestedAt을 실제 evaluator 순서로 전달한다")
    void cancellationPolicyPassesStoredVersionActorStatusStartAndRequestedAtInOrder(
            boolean storeOperator,
            Instant requestedAt
    ) {
        long actorId = storeOperator ? 33L : 11L;
        IdempotencyCommand command = cancellationCommand(
                storeOperator ? "store-operator" : "consumer",
                actorId
        );
        Reservation reservation = reservation(77L, 11L);
        stubFreshIdempotency(command, null);
        if (storeOperator) {
            given(reservationRepository.findByIdAndStoreIdForUpdate(77L, 22L))
                    .willReturn(Optional.of(reservation));
        } else {
            given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                    .willReturn(Optional.of(reservation));
        }
        given(cancellationPolicyEvaluator.evaluate(
                1L,
                storeOperator
                        ? ReservationCancellationActorType.STORE_OPERATOR
                        : ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED,
                reservation.getStartAt(),
                requestedAt
        )).willReturn(ReservationCancellationDecision.ALLOWED);
        IllegalStateException afterEvaluator = new IllegalStateException("after evaluator");
        willThrow(afterEvaluator).given(menuHoldPort).lockForTermination(77L);

        assertThatThrownBy(() -> {
            if (storeOperator) {
                invokeStoreCancellation(
                        command,
                        "운영자 취소",
                        requestedAt,
                        OPERATOR_CANCELLATION_CORRELATION
                );
            } else {
                invokeConsumerCancellation(
                        command,
                        null,
                        requestedAt,
                        CONSUMER_CANCELLATION_CORRELATION
                );
            }
        }).isSameAs(afterEvaluator);

        then(cancellationPolicyEvaluator).should().evaluate(
                1L,
                storeOperator
                        ? ReservationCancellationActorType.STORE_OPERATOR
                        : ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED,
                reservation.getStartAt(),
                requestedAt
        );
    }

    static Stream<Arguments> cancellationPolicyArgumentCases() {
        Instant startAt = Instant.parse("2026-08-03T09:00:00Z");
        return Stream.of(false, true)
                .flatMap(storeOperator -> Stream.of(
                        Arguments.of(storeOperator, startAt.minusSeconds(1)),
                        Arguments.of(storeOperator, startAt),
                        Arguments.of(storeOperator, startAt.plusSeconds(1))
                ));
    }

    @Test
    @DisplayName("unknown 저장 취소 정책의 evaluator 거절은 RESERVATION_006을 보존한다")
    void cancellationPolicyMapsUnknownVersionToReservation006() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        Reservation reservation = reservation(77L, 11L);
        stubFreshIdempotency(command, null);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(reservation));
        given(cancellationPolicyEvaluator.evaluate(
                1L,
                ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED,
                reservation.getStartAt(),
                NOW.minusSeconds(10)
        )).willReturn(ReservationCancellationDecision.REJECTED_BY_POLICY);

        assertThatThrownBy(() -> invokeConsumerCancellation(
                command, null, NOW.minusSeconds(10), CONSUMER_CANCELLATION_CORRELATION))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.CANCELLATION_NOT_ALLOWED));

        then(menuHoldPort).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(cancellationAuditRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("취소는 Reservation과 MenuHold를 먼저 잠근 뒤 정렬 합집합을 한 번 잠근다")
    void cancellationCapacityLocksMenuHoldBeforeOneSortedUnionLock() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        CancellationFixture fixture = stubSuccessfulCancellation(
                command,
                ReservationMenuHoldTerminationPresence.NO_HOLD
        );

        Object result = invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );

        assertThat(cancellationHttpStatus(result)).isEqualTo(200);
        InOrder order = inOrder(
                reservationRepository,
                cancellationPolicyEvaluator,
                menuHoldPort,
                capacityAllocationRepository,
                capacityBucketRepository
        );
        order.verify(reservationRepository)
                .findByIdAndConsumerAccountIdForUpdate(77L, 11L);
        order.verify(cancellationPolicyEvaluator).evaluate(
                1L,
                ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED,
                fixture.reservation().getStartAt(),
                NOW.minusSeconds(10)
        );
        order.verify(menuHoldPort).lockForTermination(77L);
        order.verify(capacityAllocationRepository)
                .findAllByReservationIdOrderByCapacityBucketIdAsc(77L);
        order.verify(capacityBucketRepository)
                .findLatestPolicyVersion(22L, SERVICE_DATE);
        order.verify(capacityBucketRepository).findLatestPolicyBucketIdsOverlapping(
                List.of(22L),
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 15)
        );
        order.verify(capacityBucketRepository).findAllByIdInForUpdate(
                List.of(301L, 302L, 401L)
        );
        order.verify(capacityBucketRepository)
                .findLatestPolicyVersion(22L, SERVICE_DATE);
        then(capacityBucketRepository).should(times(1))
                .findAllByIdInForUpdate(List.of(301L, 302L, 401L));
    }

    @Test
    @DisplayName("취소의 최신 후보 관찰은 scalar ID만 읽고 mutable 엔티티는 단일 잠금에서 처음 적재한다")
    void cancellationCapacityObservesScalarIdsBeforeHydratingMutableBuckets() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        stubSuccessfulCancellation(command, ReservationMenuHoldTerminationPresence.NO_HOLD);

        Throwable failure = catchThrowable(() -> invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        ));

        assertThat(failure).isNull();
        then(capacityBucketRepository).should().findLatestPolicyBucketIdsOverlapping(
                List.of(22L),
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 15)
        );
        then(capacityBucketRepository).should(never()).findLatestPolicyBucketsOverlapping(
                any(),
                any(LocalDate.class),
                any(LocalTime.class),
                any(LocalTime.class)
        );
    }

    @Test
    @DisplayName("원본 배정과 최신 carry-over 각 버킷에서 전체 인원과 팀 하나를 한 번 복구한다")
    void cancellationCapacityRestoresEachOriginalAndCurrentBucketExactlyOnce() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        CancellationFixture fixture = stubSuccessfulCancellation(
                command,
                ReservationMenuHoldTerminationPresence.NO_HOLD
        );

        invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );

        assertThat(fixture.lockedBuckets())
                .extracting(
                        ReservationCapacityBucket::getId,
                        ReservationCapacityBucket::getOccupiedPeople,
                        ReservationCapacityBucket::getOccupiedTeams
                )
                .containsExactly(
                        tuple(301L, 3, 1),
                        tuple(302L, 3, 1),
                        tuple(401L, 3, 1)
                );
        assertThat(fixture.reservation().getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        then(capacityAllocationRepository).should(times(1))
                .findAllByReservationIdOrderByCapacityBucketIdAsc(77L);
        then(capacityAllocationRepository).shouldHaveNoMoreInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "missing-allocation",
        "foreign-allocation",
        "wrong-people",
        "wrong-teams",
        "wrong-allocation-version",
        "missing-bucket",
        "underflow",
        "version-race"
    })
    @DisplayName("손상 배정·누락 버킷·underflow·정책 version race는 성공 상태와 감사를 만들지 않는다")
    void cancellationCapacityRejectsCorruptAllocationMissingBucketUnderflowAndVersionRace(
            String corruption
    ) {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        Reservation reservation = reservation(77L, 11L);
        stubFreshIdempotency(command, null);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(reservation));
        given(cancellationPolicyEvaluator.evaluate(
                1L,
                ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED,
                reservation.getStartAt(),
                NOW.minusSeconds(10)
        )).willReturn(ReservationCancellationDecision.ALLOWED);
        given(menuHoldPort.lockForTermination(77L))
                .willReturn(ReservationMenuHoldTerminationPresence.NO_HOLD);

        ReservationCapacityAllocation allocation =
                ReservationCapacityAllocation.allocate(77L, 301L, 3, 3L);
        ReservationCapacityBucket bucket = cancellationBucket(
                301L, START_TIME, LocalTime.of(19, 15), 6, 2, 3L);
        List<ReservationCapacityAllocation> allocations = List.of(allocation);
        List<ReservationCapacityBucket> lockedBuckets = List.of(bucket);
        if ("missing-allocation".equals(corruption)) {
            allocations = List.of();
        } else if ("foreign-allocation".equals(corruption)) {
            ReflectionTestUtils.setField(allocation, "reservationId", 88L);
        } else if ("wrong-people".equals(corruption)) {
            ReflectionTestUtils.setField(allocation, "occupiedPeople", 2);
        } else if ("wrong-teams".equals(corruption)) {
            ReflectionTestUtils.setField(allocation, "occupiedTeams", 2);
        } else if ("wrong-allocation-version".equals(corruption)) {
            ReflectionTestUtils.setField(allocation, "capacityPolicyVersion", 2L);
        } else if ("missing-bucket".equals(corruption)) {
            lockedBuckets = List.of();
        } else if ("underflow".equals(corruption)) {
            bucket = cancellationBucket(
                    301L, START_TIME, LocalTime.of(19, 15), 2, 0, 3L);
            lockedBuckets = List.of(bucket);
        }

        given(capacityAllocationRepository
                .findAllByReservationIdOrderByCapacityBucketIdAsc(77L))
                .willReturn(allocations);
        boolean corruptAllocation = Set.of(
                "missing-allocation",
                "foreign-allocation",
                "wrong-people",
                "wrong-teams",
                "wrong-allocation-version"
        ).contains(corruption);
        if (!corruptAllocation) {
            if ("version-race".equals(corruption)) {
                given(capacityBucketRepository.findLatestPolicyVersion(22L, SERVICE_DATE))
                        .willReturn(Optional.of(3L), Optional.of(4L));
            } else {
                given(capacityBucketRepository.findLatestPolicyVersion(22L, SERVICE_DATE))
                        .willReturn(Optional.of(3L));
            }
            given(capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
                    List.of(22L), SERVICE_DATE, START_TIME, LocalTime.of(19, 15)))
                    .willReturn(List.of(301L));
            given(capacityBucketRepository.findAllByIdInForUpdate(List.of(301L)))
                    .willReturn(lockedBuckets);
        }

        Throwable failure = catchThrowable(() -> invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        ));

        if ("underflow".equals(corruption)) {
            assertThat(failure).isInstanceOf(IllegalStateException.class);
        } else {
            assertThat(failure).isInstanceOfSatisfying(ServiceException.class, exception ->
                    assertThat(exception.getErrorCode())
                            .isEqualTo(ReservationErrorCode.CAPACITY_POLICY_CHANGED));
        }
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        then(cancellationAuditRepository).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {
        "latest-absent",
        "latest-non-positive",
        "latest-older",
        "current-wrong-store",
        "current-wrong-date",
        "current-wrong-version",
        "current-non-overlap",
        "same-version-empty",
        "same-version-id-mismatch",
        "locked-extra",
        "locked-duplicate",
        "locked-out-of-order",
        "locked-original-wrong-store",
        "locked-original-wrong-date",
        "locked-original-wrong-version"
    })
    @DisplayName("취소는 latest/current/locked fail-closed 위반을 RESERVATION_007로 거절한다")
    void cancellationCapacityRejectsInvalidLatestCurrentAndLockedContracts(String violation) {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        AtomicReference<BusinessResult<?>> succeeded = new AtomicReference<>();
        Reservation reservation = stubCancellationThroughAllocation(
                command,
                ReservationMenuHoldTerminationPresence.HOLD_PRESENT,
                succeeded
        );
        ReservationCapacityBucket original = cancellationBucket(
                301L, START_TIME, LocalTime.of(19, 15), 6, 2, 3L);
        ReservationCapacityBucket current = cancellationBucket(
                401L, START_TIME, LocalTime.of(19, 15), 6, 2, 4L);
        Optional<Long> latestVersion = Optional.of(4L);
        List<Long> currentIds = List.of(401L);
        List<ReservationCapacityBucket> lockedBuckets = List.of(original, current);
        boolean observesCurrent = true;
        boolean locksUnion = true;

        switch (violation) {
            case "latest-absent" -> {
                latestVersion = Optional.empty();
                observesCurrent = false;
                locksUnion = false;
            }
            case "latest-non-positive" -> {
                latestVersion = Optional.of(0L);
                observesCurrent = false;
                locksUnion = false;
            }
            case "latest-older" -> {
                latestVersion = Optional.of(2L);
                observesCurrent = false;
                locksUnion = false;
            }
            case "current-wrong-store" ->
                    ReflectionTestUtils.setField(current, "storeId", 23L);
            case "current-wrong-date" -> ReflectionTestUtils.setField(
                    current, "serviceDate", SERVICE_DATE.plusDays(1));
            case "current-wrong-version" ->
                    ReflectionTestUtils.setField(current, "policyVersion", 5L);
            case "current-non-overlap" -> {
                current = cancellationBucket(
                        401L, LocalTime.of(10, 0), LocalTime.of(11, 0), 6, 2, 4L);
                lockedBuckets = List.of(original, current);
            }
            case "same-version-empty" -> {
                latestVersion = Optional.of(3L);
                currentIds = List.of();
                locksUnion = false;
            }
            case "same-version-id-mismatch" -> {
                latestVersion = Optional.of(3L);
                locksUnion = false;
            }
            case "locked-extra" -> {
                ReservationCapacityBucket extra = cancellationBucket(
                        999L, START_TIME, LocalTime.of(19, 15), 6, 2, 4L);
                lockedBuckets = List.of(original, current, extra);
            }
            case "locked-duplicate" -> lockedBuckets = List.of(original, original);
            case "locked-out-of-order" -> lockedBuckets = List.of(current, original);
            case "locked-original-wrong-store" ->
                    ReflectionTestUtils.setField(original, "storeId", 23L);
            case "locked-original-wrong-date" -> ReflectionTestUtils.setField(
                    original, "serviceDate", SERVICE_DATE.plusDays(1));
            case "locked-original-wrong-version" ->
                    ReflectionTestUtils.setField(original, "policyVersion", 2L);
            default -> throw new IllegalArgumentException("unsupported violation: " + violation);
        }

        given(capacityBucketRepository.findLatestPolicyVersion(22L, SERVICE_DATE))
                .willReturn(latestVersion);
        if (observesCurrent) {
            given(capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
                    List.of(22L), SERVICE_DATE, START_TIME, LocalTime.of(19, 15)))
                    .willReturn(currentIds);
        }
        if (locksUnion) {
            TreeSet<Long> union = new TreeSet<>(List.of(301L));
            union.addAll(currentIds);
            given(capacityBucketRepository.findAllByIdInForUpdate(List.copyOf(union)))
                    .willReturn(lockedBuckets);
        }

        Throwable failure = catchThrowable(() -> invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        ));

        assertThat(failure).isInstanceOfSatisfying(ServiceException.class, exception ->
                assertThat(exception.getErrorCode())
                        .isEqualTo(ReservationErrorCode.CAPACITY_POLICY_CHANGED));
        assertThat(reservation.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(succeeded).hasNullValue();
        then(menuHoldPort).should(never()).release(anyLong(), anyString());
        then(cancellationAuditRepository).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @ValueSource(strings = {"NO_HOLD", "HOLD_PRESENT", "WRONG_ID", "WRONG_OUTCOME"})
    @DisplayName("홀드가 있을 때만 exact correlation으로 RELEASED 결과를 검증해 해제한다")
    void cancellationCapacityReleasesOnlyPresentMenuHoldWithExactCorrelation(String holdCase) {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        boolean holdPresent = !"NO_HOLD".equals(holdCase);
        boolean expectedToComplete = !"WRONG_ID".equals(holdCase)
                && !"WRONG_OUTCOME".equals(holdCase);
        CancellationFixture fixture = stubCancellation(
                command,
                holdPresent
                        ? ReservationMenuHoldTerminationPresence.HOLD_PRESENT
                        : ReservationMenuHoldTerminationPresence.NO_HOLD,
                expectedToComplete
        );
        if ("WRONG_ID".equals(holdCase)) {
            given(menuHoldPort.release(anyLong(), anyString()))
                    .willReturn(ReservationMenuHoldResult.released(78L));
        } else if ("WRONG_OUTCOME".equals(holdCase)) {
            given(menuHoldPort.release(anyLong(), anyString()))
                    .willReturn(ReservationMenuHoldResult.confirmed(77L));
        }

        Throwable failure = catchThrowable(() -> invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        ));

        if ("WRONG_ID".equals(holdCase) || "WRONG_OUTCOME".equals(holdCase)) {
            assertThat(failure).isInstanceOf(IllegalStateException.class);
            assertThat(fixture.reservation().getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
            then(cancellationAuditRepository).shouldHaveNoInteractions();
            return;
        }
        assertThat(failure).isNull();
        if (holdPresent) {
            then(menuHoldPort).should().release(
                    77L, CONSUMER_CANCELLATION_CORRELATION);
        } else {
            then(menuHoldPort).should(never()).release(anyLong(), anyString());
        }
    }

    @Test
    @DisplayName("HOLD_PRESENT 해제 후 managed Reservation을 재조회해 취소·감사·응답에 사용한다")
    void cancellationHoldPresentRehydratesManagedReservationAfterReleaseClearsContext() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        CancellationFixture fixture = stubSuccessfulCancellation(
                command,
                ReservationMenuHoldTerminationPresence.HOLD_PRESENT
        );
        Reservation original = fixture.reservation();
        Reservation rehydrated = reservation(77L, 11L);
        lenient().when(reservationRepository.findById(77L))
                .thenReturn(Optional.of(rehydrated));

        Object result = invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );

        assertThat(original.getStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(original.getCancelledAt()).isNull();
        assertThat(rehydrated.getStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(rehydrated.getCancelledAt()).isEqualTo(NOW);
        assertThat(cancellationData(result).status()).isEqualTo("CANCELLED");

        InOrder order = inOrder(
                menuHoldPort,
                reservationRepository,
                cancellationAuditRepository
        );
        order.verify(menuHoldPort).release(
                77L, CONSUMER_CANCELLATION_CORRELATION);
        order.verify(reservationRepository).findById(77L);
        order.verify(cancellationAuditRepository).saveAndFlush(
                any(ReservationCancellationAudit.class));
    }

    @Test
    @DisplayName("취소 성공 감사와 Reservation cancelledAt은 한 번 얻은 같은 occurredAt을 쓴다")
    void cancellationAuditUsesOneOccurredAtForReservationAndAudit() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        CancellationFixture fixture = stubSuccessfulCancellation(
                command,
                ReservationMenuHoldTerminationPresence.NO_HOLD
        );

        invokeConsumerCancellation(
                command,
                "사용자 취소",
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );

        ArgumentCaptor<ReservationCancellationAudit> auditCaptor =
                ArgumentCaptor.forClass(ReservationCancellationAudit.class);
        then(cancellationAuditRepository).should().saveAndFlush(auditCaptor.capture());
        ReservationCancellationAudit audit = auditCaptor.getValue();
        assertThat(audit.getReservationId()).isEqualTo(77L);
        assertThat(audit.getActorType()).isEqualTo(ReservationCancellationActorType.CONSUMER);
        assertThat(audit.getActorId()).isEqualTo(11L);
        assertThat(audit.getCancellationReason()).isEqualTo("사용자 취소");
        assertThat(audit.getRequestedAt()).isEqualTo(NOW.minusSeconds(10));
        assertThat(audit.getOccurredAt()).isEqualTo(NOW);
        assertThat(audit.getBeforeStatus()).isEqualTo(ReservationStatus.CONFIRMED);
        assertThat(audit.getAfterStatus()).isEqualTo(ReservationStatus.CANCELLED);
        assertThat(audit.getCancellationPolicyVersion()).isEqualTo(1L);
        assertThat(audit.getCapacityPolicyVersion()).isEqualTo(3L);
        assertThat(audit.getCommandId()).isEqualTo(CONSUMER_CANCELLATION_CORRELATION);
        assertThat(fixture.reservation().getCancelledAt()).isSameAs(audit.getOccurredAt());
        assertThat(notificationEvents).singleElement().satisfies(event -> {
            assertThat(event.purpose()).isEqualTo(NotificationPurpose.RESERVATION_CANCELLED);
            assertThat(event.resourceVersion()).isEqualTo(2L);
            assertThat(event.occurredAt().toInstant()).isEqualTo(audit.getOccurredAt());
            assertThat(event.correlationId()).isEqualTo(CONSUMER_CANCELLATION_CORRELATION);
        });
    }

    @Test
    @DisplayName("운영자 공백 사유는 trimming 없이 감사와 결과에 그대로 보존한다")
    void cancellationAuditAcceptsWhitespaceOperatorReason() {
        IdempotencyCommand command = cancellationCommand("store-operator", 33L);
        stubSuccessfulCancellation(command, ReservationMenuHoldTerminationPresence.NO_HOLD);

        Object result = invokeStoreCancellation(
                command,
                " ",
                NOW.minusSeconds(10),
                OPERATOR_CANCELLATION_CORRELATION
        );

        ArgumentCaptor<ReservationCancellationAudit> auditCaptor =
                ArgumentCaptor.forClass(ReservationCancellationAudit.class);
        then(cancellationAuditRepository).should().saveAndFlush(auditCaptor.capture());
        assertThat(auditCaptor.getValue().getActorType())
                .isEqualTo(ReservationCancellationActorType.STORE_OPERATOR);
        assertThat(auditCaptor.getValue().getActorId()).isEqualTo(33L);
        assertThat(auditCaptor.getValue().getCancellationReason()).isEqualTo(" ");
        assertThat(auditCaptor.getValue().getCommandId())
                .isEqualTo(OPERATOR_CANCELLATION_CORRELATION);
        assertThat(cancellationData(result).cancelledBy()).isEqualTo("STORE_OPERATOR");
        assertThat(cancellationData(result).cancellationReason()).isEqualTo(" ");
        assertThat(notificationEvents).singleElement().satisfies(event -> {
            assertThat(event.purpose()).isEqualTo(NotificationPurpose.RESERVATION_CANCELLED);
            assertThat(event.correlationId()).isEqualTo(OPERATOR_CANCELLATION_CORRELATION);
        });
    }

    @Test
    @DisplayName("소비자 취소는 supplementary 문자 500 code point 사유를 허용한다")
    void cancellationAcceptsFiveHundredCodePointConsumerReason() {
        String reason = "😀".repeat(500);
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        stubSuccessfulCancellation(command, ReservationMenuHoldTerminationPresence.NO_HOLD);

        Object result = invokeConsumerCancellation(
                command,
                reason,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );

        assertThat(cancellationData(result).cancellationReason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("운영자 취소는 supplementary 문자 500 code point 사유를 허용한다")
    void cancellationAcceptsFiveHundredCodePointOperatorReason() {
        String reason = "😀".repeat(500);
        IdempotencyCommand command = cancellationCommand("store-operator", 33L);
        stubSuccessfulCancellation(command, ReservationMenuHoldTerminationPresence.NO_HOLD);

        Object result = invokeStoreCancellation(
                command,
                reason,
                NOW.minusSeconds(10),
                OPERATOR_CANCELLATION_CORRELATION
        );

        assertThat(cancellationData(result).cancellationReason()).isEqualTo(reason);
    }

    @Test
    @DisplayName("취소 서비스는 supplementary 문자 501 code point 사유를 거절한다")
    void cancellationRejectsFiveHundredOneCodePointReason() {
        String reason = "😀".repeat(501);

        assertValidationFailure(() -> invokeConsumerCancellation(
                cancellationCommand("consumer", 11L),
                reason,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        ));
        assertValidationFailure(() -> invokeStoreCancellation(
                cancellationCommand("store-operator", 33L),
                reason,
                NOW.minusSeconds(10),
                OPERATOR_CANCELLATION_CORRELATION
        ));
    }

    @Test
    @DisplayName("감사 저장 실패는 성공 BusinessResult를 만들지 않는다")
    void cancellationAuditFailurePreventsSucceededOutcome() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        AtomicReference<BusinessResult<?>> succeeded = new AtomicReference<>();
        CancellationFixture fixture = stubCancellation(
                command,
                ReservationMenuHoldTerminationPresence.NO_HOLD,
                false,
                succeeded
        );
        given(reservationRepository.findById(77L))
                .willReturn(Optional.of(fixture.reservation()));
        IllegalStateException auditFailure = new IllegalStateException("audit write failed");
        given(cancellationAuditRepository.saveAndFlush(
                any(ReservationCancellationAudit.class)))
                .willThrow(auditFailure);

        assertThatThrownBy(() -> invokeConsumerCancellation(
                command,
                null,
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        )).isSameAs(auditFailure);

        assertThat(succeeded).hasNullValue();
        assertThat(fixture.reservation().getCancelledAt()).isEqualTo(NOW);
        assertThat(notificationEvents).isEmpty();
        then(menuHoldPort).should(never()).findSnapshots(anyLong());
    }

    @Test
    @DisplayName("멱등 저장 결과는 200·SUCCESS·RESERVATION·ID와 detail data만 담는다")
    void cancellationDetailStoresOnlyStatusCodeResourceAndData() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        AtomicReference<BusinessResult<?>> captured = new AtomicReference<>();
        stubCancellation(
                command,
                ReservationMenuHoldTerminationPresence.NO_HOLD,
                true,
                captured
        );

        Object result = invokeConsumerCancellation(
                command,
                "사용자 취소",
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );

        assertThat(captured.get()).isNotNull();
        assertThat(captured.get().httpStatus()).isEqualTo(200);
        assertThat(captured.get().responseCode()).isEqualTo("SUCCESS");
        assertThat(captured.get().resourceType()).isEqualTo("RESERVATION");
        assertThat(captured.get().resourceId()).isEqualTo("77");
        assertThat(captured.get().data()).isEqualTo(cancellationData(result));
        JsonNode storedData = new ObjectMapper().valueToTree(captured.get().data());
        assertThat(storedData.has("code")).isFalse();
        assertThat(storedData.has("message")).isFalse();
        assertThat(storedData.has("data")).isFalse();
    }

    @Test
    @DisplayName("detail replay는 저장 JSON을 반환하고 성공 감사를 추가하지 않는다")
    void cancellationDetailReplayDoesNotWriteAnotherAudit() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        Reservation cancelled = reservation(77L, 11L);
        cancelled.cancel(NOW.minusSeconds(1));
        ReservationDetailResponse stored = ReservationDetailResponse.from(
                cancelled,
                List.of(),
                ReservationCancellationActorType.CONSUMER,
                "원래 사유"
        );
        given(idempotencyExecutor.execute(eq(command), any())).willReturn(
                new IdempotentOutcome(
                        true,
                        200,
                        "SUCCESS",
                        "RESERVATION",
                        "77",
                        new ObjectMapper().valueToTree(stored)
                )
        );

        Object replay = invokeConsumerCancellation(
                command,
                "원래 사유",
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );

        assertThat(cancellationData(replay)).isEqualTo(stored);
        then(cancellationAuditRepository).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("후속 소비자·운영자 detail은 optional 감사를 결합하고 legacy 취소는 null을 반환한다")
    void cancellationDetailGetUsesOptionalAuditAndLegacyNulls() {
        IdempotencyCommand command = cancellationCommand("consumer", 11L);
        CancellationFixture fixture = stubSuccessfulCancellation(
                command,
                ReservationMenuHoldTerminationPresence.NO_HOLD
        );
        invokeConsumerCancellation(
                command,
                "사용자 취소",
                NOW.minusSeconds(10),
                CONSUMER_CANCELLATION_CORRELATION
        );
        ArgumentCaptor<ReservationCancellationAudit> auditCaptor =
                ArgumentCaptor.forClass(ReservationCancellationAudit.class);
        then(cancellationAuditRepository).should().saveAndFlush(auditCaptor.capture());
        ReservationCancellationAudit audit = auditCaptor.getValue();
        given(reservationRepository.findByIdAndConsumerAccountId(77L, 11L))
                .willReturn(Optional.of(fixture.reservation()));
        given(reservationRepository.findByIdAndStoreId(77L, 22L))
                .willReturn(Optional.of(fixture.reservation()));
        given(cancellationAuditRepository.findByReservationId(77L))
                .willReturn(Optional.of(audit), Optional.empty());
        given(menuHoldPort.findSnapshots(77L)).willReturn(List.of());

        ReservationDetailResponse consumer =
                reservationService.getConsumerReservation(11L, 77L);
        ReservationDetailResponse operator =
                reservationService.getStoreReservation(33L, 22L, 77L);

        assertThat(consumer.cancelledBy()).isEqualTo("CONSUMER");
        assertThat(consumer.cancellationReason()).isEqualTo("사용자 취소");
        assertThat(operator.cancelledBy()).isNull();
        assertThat(operator.cancellationReason()).isNull();
        then(cancellationAuditRepository).should(times(2)).findByReservationId(77L);
    }

    @Test
    @DisplayName("상태 필터가 없으면 계정 범위 전체 조회를 사용한다")
    void findsAllConsumerHistoryWithoutStatusFilter() {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, 0, 20, null);
        given(reservationRepository.findAllByConsumerAccountId(
                eq(11L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        ReservationHistoryPageResponse response =
                reservationService.getConsumerReservationHistory(11L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByConsumerAccountId(eq(11L), pageable.capture());
        then(reservationRepository).should(never())
                .findAllByConsumerAccountIdAndStatus(
                        anyLong(),
                        any(ReservationStatus.class),
                        any(Pageable.class)
                );
        then(consumerAccountService).should().getMe(11L);
        assertThat(pageable.getValue().getPageNumber()).isZero();
        assertThat(pageable.getValue().getPageSize()).isEqualTo(20);
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("상태 필터가 있으면 계정과 상태를 함께 제한한다")
    void filtersConsumerHistoryByStatus() {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(
                        "CANCELLED",
                        2,
                        10,
                        "serviceDate,asc"
                );
        given(reservationRepository.findAllByConsumerAccountIdAndStatus(
                eq(11L),
                eq(ReservationStatus.CANCELLED),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(2, 10)));

        // when
        reservationService.getConsumerReservationHistory(11L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByConsumerAccountIdAndStatus(
                        eq(11L),
                        eq(ReservationStatus.CANCELLED),
                        pageable.capture()
                );
        then(reservationRepository).should(never())
                .findAllByConsumerAccountId(anyLong(), any(Pageable.class));
        then(consumerAccountService).should().getMe(11L);
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(2);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @ParameterizedTest(name = "{0} 정렬")
    @MethodSource("approvedSortCases")
    @DisplayName("승인된 정렬은 같은 방향의 예약 ID 보조 정렬을 사용한다")
    void addsReservationIdTieBreaker(
            String externalSort,
            String primaryProperty,
            Sort.Direction direction
    ) {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, 0, 20, externalSort);
        given(reservationRepository.findAllByConsumerAccountId(
                eq(11L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        reservationService.getConsumerReservationHistory(11L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByConsumerAccountId(eq(11L), pageable.capture());
        assertThat(pageable.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple(primaryProperty, direction),
                        tuple("id", direction)
                );
    }

    @Test
    @DisplayName("소비자 serviceDate 정렬은 embedded 경로와 같은 방향의 ID 보조 정렬을 사용한다")
    void sortsHistoryByEmbeddedServiceDateThenIdInSameDirection() {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(
                        null,
                        0,
                        20,
                        "serviceDate,desc"
                );
        given(reservationRepository.findAllByConsumerAccountId(
                eq(11L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        reservationService.getConsumerReservationHistory(11L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByConsumerAccountId(eq(11L), pageable.capture());
        assertThat(pageable.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("timeSnapshot.serviceDate", Sort.Direction.DESC),
                        tuple("id", Sort.Direction.DESC)
                );
    }

    @ParameterizedTest
    @NullSource
    @ValueSource(longs = {0L, -1L})
    @DisplayName("유효하지 않은 소비자 계정 ID는 COMMON_001로 거절한다")
    void rejectsInvalidConsumerAccountId(Long consumerAccountId) {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, null, null, null);

        // when & then
        assertValidationFailure(() ->
                reservationService.getConsumerReservationHistory(
                        consumerAccountId,
                        request
                ));
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("조회 조건이 없으면 COMMON_001로 거절한다")
    void rejectsNullRequest() {
        // when & then
        assertValidationFailure(() ->
                reservationService.getConsumerReservationHistory(11L, null));
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("정지된 소비자 계정은 예약 내역을 조회하지 않는다")
    void rejectsRestrictedConsumerBeforeQuery() {
        // given
        ReservationHistorySearchRequest request =
                ReservationHistorySearchRequest.from(null, null, null, null);
        given(consumerAccountService.getMe(11L))
                .willThrow(new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED));

        // when & then
        assertThatThrownBy(() ->
                reservationService.getConsumerReservationHistory(11L, request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("활성 소비자 확인과 소유 범위 조회 뒤 메뉴 스냅샷으로 본인 상세를 반환한다")
    void findsConsumerDetailAfterActiveAccountCheck() {
        // given
        Reservation reservation = reservation(77L, 11L);
        List<ReservationMenuHoldItemSnapshot> snapshots = List.of(
                new ReservationMenuHoldItemSnapshot(91L, "아메리카노", 4_500L, 2),
                new ReservationMenuHoldItemSnapshot(92L, "바스크 치즈케이크", 7_000L, 1)
        );
        given(reservationRepository.findByIdAndConsumerAccountId(77L, 11L))
                .willReturn(Optional.of(reservation));
        given(menuHoldPort.findSnapshots(77L))
                .willReturn(snapshots);

        // when
        ReservationDetailResponse response =
                reservationService.getConsumerReservation(11L, 77L);

        // then
        assertThat(response.reservationId()).isEqualTo("77");
        assertThat(response.menuSelections())
                .extracting(selection -> selection.menuId())
                .containsExactly("91", "92");
        InOrder order = inOrder(
                consumerAccountService,
                reservationRepository,
                menuHoldPort
        );
        order.verify(consumerAccountService).getMe(11L);
        order.verify(reservationRepository).findByIdAndConsumerAccountId(77L, 11L);
        order.verify(menuHoldPort).findSnapshots(77L);
        then(reservationRepository).should(never()).findById(anyLong());
    }

    @Test
    @DisplayName("없는 예약과 다른 소비자 예약은 같은 숨김 404를 반환한다")
    void returnsSameHiddenNotFoundForMissingOrForeignConsumerReservation() {
        // given
        given(reservationRepository.findByIdAndConsumerAccountId(77L, 11L))
                .willReturn(Optional.empty());
        given(reservationRepository.findByIdAndConsumerAccountId(88L, 11L))
                .willReturn(Optional.empty());

        // when & then
        assertHiddenReservationNotFound(() ->
                reservationService.getConsumerReservation(11L, 77L));
        assertHiddenReservationNotFound(() ->
                reservationService.getConsumerReservation(11L, 88L));
        then(reservationRepository).should(never()).findById(anyLong());
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("0 이하 예약 ID도 복합 조회 뒤 같은 숨김 404를 반환한다")
    void returnsHiddenNotFoundForNonPositiveConsumerReservationId() {
        // given
        given(reservationRepository.findByIdAndConsumerAccountId(0L, 11L))
                .willReturn(Optional.empty());
        given(reservationRepository.findByIdAndConsumerAccountId(-1L, 11L))
                .willReturn(Optional.empty());

        // when & then
        assertHiddenReservationNotFound(() ->
                reservationService.getConsumerReservation(11L, 0L));
        assertHiddenReservationNotFound(() ->
                reservationService.getConsumerReservation(11L, -1L));
        then(consumerAccountService).should(times(2)).getMe(11L);
        then(reservationRepository).should()
                .findByIdAndConsumerAccountId(0L, 11L);
        then(reservationRepository).should()
                .findByIdAndConsumerAccountId(-1L, 11L);
        then(reservationRepository).should(never()).findById(anyLong());
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("소유 범위에서 숨겨진 예약은 메뉴 거래 스냅샷을 조회하지 않는다")
    void doesNotQueryMenuSnapshotWhenConsumerReservationIsHidden() {
        // given
        given(reservationRepository.findByIdAndConsumerAccountId(77L, 11L))
                .willReturn(Optional.empty());

        // when & then
        assertHiddenReservationNotFound(() ->
                reservationService.getConsumerReservation(11L, 77L));
        then(menuHoldPort).shouldHaveNoInteractions();
        then(reservationRepository).should(never()).findById(anyLong());
    }

    @Test
    @DisplayName("정지된 소비자는 예약이나 메뉴 거래 스냅샷보다 먼저 거절한다")
    void rejectsRestrictedConsumerBeforeReservationLookup() {
        // given
        given(consumerAccountService.getMe(11L))
                .willThrow(new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED));

        // when & then
        assertThatThrownBy(() ->
                reservationService.getConsumerReservation(11L, 77L))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED));
        then(reservationRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("매장 관리 권한과 소유 범위 조회 뒤 메뉴 스냅샷으로 운영자 상세를 반환한다")
    void findsStoreDetailAfterManagementAuthorization() {
        // given
        Reservation reservation = reservation(77L, 11L);
        List<ReservationMenuHoldItemSnapshot> snapshots = List.of(
                new ReservationMenuHoldItemSnapshot(91L, "아메리카노", 4_500L, 2),
                new ReservationMenuHoldItemSnapshot(92L, "바스크 치즈케이크", 7_000L, 1)
        );
        given(reservationRepository.findByIdAndStoreId(77L, 22L))
                .willReturn(Optional.of(reservation));
        given(menuHoldPort.findSnapshots(77L))
                .willReturn(snapshots);

        // when
        ReservationDetailResponse response =
                reservationService.getStoreReservation(33L, 22L, 77L);

        // then
        assertThat(response.reservationId()).isEqualTo("77");
        assertThat(response.storeId()).isEqualTo("22");
        assertThat(response.menuSelections())
                .extracting(selection -> selection.menuId())
                .containsExactly("91", "92");
        InOrder order = inOrder(
                storeService,
                reservationRepository,
                menuHoldPort
        );
        order.verify(storeService).requireManagementOwnership(33L, 22L);
        order.verify(reservationRepository).findByIdAndStoreId(77L, 22L);
        order.verify(menuHoldPort).findSnapshots(77L);
        then(reservationRepository).should(never()).findById(anyLong());
    }

    @Test
    @DisplayName("매장 관리 권한이 없으면 예약이나 메뉴 스냅샷을 조회하지 않는다")
    void doesNotQueryReservationWhenManagementAuthorizationFails() {
        // given
        willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED))
                .given(storeService)
                .requireManagementOwnership(33L, 22L);

        // when & then
        assertThatThrownBy(() ->
                reservationService.getStoreReservation(33L, 22L, 77L))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.ACCESS_DENIED));
        then(reservationRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("없는 예약과 다른 매장 예약은 같은 숨김 404를 반환한다")
    void returnsHiddenNotFoundForReservationOutsideStoreScope() {
        // given
        given(reservationRepository.findByIdAndStoreId(77L, 22L))
                .willReturn(Optional.empty());
        given(reservationRepository.findByIdAndStoreId(88L, 22L))
                .willReturn(Optional.empty());

        // when & then
        assertHiddenReservationNotFound(() ->
                reservationService.getStoreReservation(33L, 22L, 77L));
        assertHiddenReservationNotFound(() ->
                reservationService.getStoreReservation(33L, 22L, 88L));
        then(reservationRepository).should(never()).findById(anyLong());
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("0 이하 운영자 상세 예약 ID도 복합 조회 뒤 같은 숨김 404를 반환한다")
    void returnsHiddenNotFoundForNonPositiveStoreReservationId() {
        // given
        given(reservationRepository.findByIdAndStoreId(0L, 22L))
                .willReturn(Optional.empty());
        given(reservationRepository.findByIdAndStoreId(-1L, 22L))
                .willReturn(Optional.empty());

        // when & then
        assertHiddenReservationNotFound(() ->
                reservationService.getStoreReservation(33L, 22L, 0L));
        assertHiddenReservationNotFound(() ->
                reservationService.getStoreReservation(33L, 22L, -1L));
        then(storeService).should(times(2)).requireManagementOwnership(33L, 22L);
        then(reservationRepository).should().findByIdAndStoreId(0L, 22L);
        then(reservationRepository).should().findByIdAndStoreId(-1L, 22L);
        then(reservationRepository).should(never()).findById(anyLong());
        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("매장 범위에서 숨겨진 예약은 메뉴 거래 스냅샷을 조회하지 않는다")
    void doesNotQueryMenuSnapshotWhenStoreReservationIsHidden() {
        // given
        given(reservationRepository.findByIdAndStoreId(77L, 22L))
                .willReturn(Optional.empty());

        // when & then
        assertHiddenReservationNotFound(() ->
                reservationService.getStoreReservation(33L, 22L, 77L));
        then(menuHoldPort).shouldHaveNoInteractions();
        then(reservationRepository).should(never()).findById(anyLong());
    }

    @Test
    @DisplayName("운영자 목록은 매장 관리 권한을 확인한 뒤 대상 매장만 조회한다")
    void findsStoreReservationsAfterManagementAuthorization() {
        // given
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, 0, 20, null);
        given(reservationRepository.findAllByStoreId(
                eq(22L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        StoreReservationPageResponse response =
                reservationService.getStoreReservations(33L, 22L, request);

        // then
        then(storeService).should().requireManagementOwnership(33L, 22L);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByStoreId(eq(22L), pageable.capture());
        assertThat(pageable.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("timeSnapshot.serviceDate", Sort.Direction.ASC),
                        tuple("id", Sort.Direction.ASC)
                );
        assertThat(response.items()).isEmpty();
    }

    @Test
    @DisplayName("운영자 serviceDate 정렬은 embedded 경로와 같은 방향의 ID 보조 정렬을 사용한다")
    void sortsStoreReservationsByEmbeddedServiceDateThenIdInSameDirection() {
        // given
        StoreReservationSearchRequest request = StoreReservationSearchRequest.from(
                null,
                null,
                0,
                20,
                "serviceDate,desc"
        );
        given(reservationRepository.findAllByStoreId(
                eq(22L),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(0, 20)));

        // when
        reservationService.getStoreReservations(33L, 22L, request);

        // then
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByStoreId(eq(22L), pageable.capture());
        assertThat(pageable.getValue().getSort().stream())
                .extracting(Sort.Order::getProperty, Sort.Order::getDirection)
                .containsExactly(
                        tuple("timeSnapshot.serviceDate", Sort.Direction.DESC),
                        tuple("id", Sort.Direction.DESC)
                );
    }

    @Test
    @DisplayName("운영자 목록은 서비스 날짜와 상태를 함께 제한한다")
    void filtersStoreReservationsByServiceDateAndStatus() {
        // given
        LocalDate serviceDate = LocalDate.of(2026, 8, 1);
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(
                        serviceDate,
                        "CONFIRMED",
                        1,
                        10,
                        "createdAt,desc"
                );
        given(reservationRepository.findAllByStoreIdAndTimeSnapshotServiceDateAndStatus(
                eq(22L),
                eq(serviceDate),
                eq(ReservationStatus.CONFIRMED),
                any(Pageable.class)
        )).willReturn(Page.empty(PageRequest.of(1, 10)));

        // when
        reservationService.getStoreReservations(33L, 22L, request);

        // then
        then(storeService).should().requireManagementOwnership(33L, 22L);
        ArgumentCaptor<Pageable> pageable = ArgumentCaptor.forClass(Pageable.class);
        then(reservationRepository).should()
                .findAllByStoreIdAndTimeSnapshotServiceDateAndStatus(
                        eq(22L),
                        eq(serviceDate),
                        eq(ReservationStatus.CONFIRMED),
                        pageable.capture()
                );
        assertThat(pageable.getValue().getPageNumber()).isEqualTo(1);
        assertThat(pageable.getValue().getPageSize()).isEqualTo(10);
    }

    @Test
    @DisplayName("매장 관리 권한이 없으면 예약 목록을 조회하지 않는다")
    void rejectsStoreAccessBeforeQuery() {
        // given
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, null, null, null);
        willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED))
                .given(storeService)
                .requireManagementOwnership(33L, 22L);

        // when & then
        assertThatThrownBy(() ->
                reservationService.getStoreReservations(33L, 22L, request))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(StoreErrorCode.ACCESS_DENIED));
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    @DisplayName("운영자·매장 ID 또는 조회 조건이 유효하지 않으면 조회하지 않는다")
    void rejectsInvalidStoreQueryScope() {
        // given
        StoreReservationSearchRequest request =
                StoreReservationSearchRequest.from(null, null, null, null, null);

        // when & then
        assertValidationFailure(() ->
                reservationService.getStoreReservations(0L, 22L, request));
        assertValidationFailure(() ->
                reservationService.getStoreReservations(33L, 0L, request));
        assertValidationFailure(() ->
                reservationService.getStoreReservations(33L, 22L, null));
        then(storeService).shouldHaveNoInteractions();
        then(reservationRepository).shouldHaveNoInteractions();
    }

    @Test
    void fulfillmentChecksCurrentOwnershipBeforeIdempotencyClaim() {
        willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED))
                .given(storeService)
                .requireManagementOwnership(OPERATOR_ID, STORE_ID);

        assertServiceError(() -> reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        ), StoreErrorCode.ACCESS_DENIED);

        then(idempotencyExecutor).shouldHaveNoInteractions();
    }

    @Test
    void fulfillmentTransitionsReservationAndPresentHoldWithoutResourceRestore() {
        stubFreshConfirmed(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        given(menuHoldPort.fulfill(anyLong(), anyString()))
                .willReturn(ReservationMenuHoldResult.fulfilled(RESERVATION_ID));

        ReservationFulfillmentCommandResult result =
                reservationService.fulfillStoreReservation(
                        OPERATOR_ID,
                        STORE_ID,
                        RESERVATION_ID,
                        fulfillmentCommand(),
                        REQUESTED_AT,
                        FULFILLMENT_CORRELATION
                );

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data().status()).isEqualTo("FULFILLED");
        assertThat(notificationEvents).isEmpty();
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(capacityAllocationRepository).shouldHaveNoInteractions();
    }

    @Test
    void fulfillmentUsesOwnershipOnlyAndNeverChecksTransactionEligibility() {
        stubFreshIdempotency(fulfillmentCommand(), null);
        given(reservationRepository.findByIdAndStoreIdForUpdate(
                RESERVATION_ID,
                STORE_ID
        )).willReturn(Optional.of(confirmedReservation()));
        given(menuHoldPort.lockForTermination(RESERVATION_ID))
                .willReturn(ReservationMenuHoldTerminationPresence.NO_HOLD);
        given(fulfillmentAuditRepository.saveAndFlush(
                any(ReservationFulfillmentAudit.class)
        )).willAnswer(invocation -> invocation.getArgument(0));

        reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        );

        then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
        then(storeService).shouldHaveNoMoreInteractions();
        then(storeTransactionEligibilityService).shouldHaveNoInteractions();
    }

    @Test
    void replayRechecksOwnershipAndDoesNotReadOrMutateReservationHoldOrAudit() {
        given(idempotencyExecutor.execute(eq(fulfillmentCommand()), any()))
                .willReturn(successfulFulfillmentOutcome());

        ReservationFulfillmentCommandResult result =
                reservationService.fulfillStoreReservation(
                        OPERATOR_ID,
                        STORE_ID,
                        RESERVATION_ID,
                        fulfillmentCommand(),
                        REQUESTED_AT,
                        FULFILLMENT_CORRELATION
                );

        then(storeService).should().requireManagementOwnership(OPERATOR_ID, STORE_ID);
        then(reservationRepository).shouldHaveNoInteractions();
        then(menuHoldPort).shouldHaveNoInteractions();
        then(fulfillmentAuditRepository).shouldHaveNoInteractions();
        assertThat(result.httpStatus()).isEqualTo(200);
    }

    @Test
    void noHoldNeverCallsFulfillAndDoesNotCreateOrRestoreResources() {
        stubFreshConfirmed(ReservationMenuHoldTerminationPresence.NO_HOLD);

        reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        );

        then(menuHoldPort).should(never()).fulfill(anyLong(), anyString());
        then(capacityBucketRepository).shouldHaveNoInteractions();
        then(capacityAllocationRepository).shouldHaveNoInteractions();
    }

    @Test
    void acceptsAlreadyFulfilledMenuHoldAsThePublicFulfilledOutcome() {
        stubFreshConfirmed(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        given(menuHoldPort.fulfill(RESERVATION_ID, FULFILLMENT_CORRELATION))
                .willReturn(ReservationMenuHoldResult.fulfilled(RESERVATION_ID));

        reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        );

        then(fulfillmentAuditRepository).should().saveAndFlush(any());
    }

    @ParameterizedTest
    @EnumSource(
            value = ReservationStatus.class,
            names = {"CONFIRMED"},
            mode = EnumSource.Mode.EXCLUDE
    )
    void rejectsEveryNonConfirmedStateBeforeLockingMenuHold(ReservationStatus status) {
        stubFreshIdempotency(fulfillmentCommand(), null);
        given(reservationRepository.findByIdAndStoreIdForUpdate(
                RESERVATION_ID,
                STORE_ID
        )).willReturn(Optional.of(reservationIn(status)));

        assertServiceError(() -> reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        ), ReservationErrorCode.INVALID_STATE_TRANSITION);

        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @Test
    void usesOneOccurredAtLocksInOrderAndNeverRehydratesAfterMenuHoldFulfill() {
        stubFreshConfirmed(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        given(menuHoldPort.fulfill(anyLong(), anyString()))
                .willReturn(ReservationMenuHoldResult.fulfilled(RESERVATION_ID));

        reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        );

        InOrder order = inOrder(
                reservationRepository,
                menuHoldPort,
                fulfillmentAuditRepository
        );
        order.verify(reservationRepository)
                .findByIdAndStoreIdForUpdate(RESERVATION_ID, STORE_ID);
        order.verify(menuHoldPort).lockForTermination(RESERVATION_ID);
        order.verify(menuHoldPort).fulfill(
                RESERVATION_ID, FULFILLMENT_CORRELATION);
        ArgumentCaptor<ReservationFulfillmentAudit> audit =
                ArgumentCaptor.forClass(ReservationFulfillmentAudit.class);
        order.verify(fulfillmentAuditRepository).saveAndFlush(audit.capture());
        assertThat(audit.getValue().getOccurredAt()).isEqualTo(OCCURRED_AT);
        then(reservationRepository).should(never()).findById(anyLong());
        then(clock).should().instant();
    }

    @Test
    void propagatesAuditFailureSoTheTransactionCanRollBack() {
        stubFreshConfirmed(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        given(menuHoldPort.fulfill(anyLong(), anyString()))
                .willReturn(ReservationMenuHoldResult.fulfilled(RESERVATION_ID));
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("audit insert");
        given(fulfillmentAuditRepository.saveAndFlush(any())).willThrow(failure);

        assertThatThrownBy(() -> reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        )).isSameAs(failure);
    }

    @Test
    void missingOrWrongStoreReservationIsHiddenAsReservation001() {
        stubFreshIdempotency(fulfillmentCommand(), null);
        given(reservationRepository.findByIdAndStoreIdForUpdate(
                RESERVATION_ID,
                STORE_ID
        )).willReturn(Optional.empty());

        assertServiceError(() -> reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        ), ReservationErrorCode.RESERVATION_NOT_FOUND);

        then(menuHoldPort).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @MethodSource("inconsistentFulfillmentHoldResults")
    void rejectsNullOrInconsistentMenuHoldResult(ReservationMenuHoldResult result) {
        stubFreshConfirmed(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        given(menuHoldPort.fulfill(RESERVATION_ID, FULFILLMENT_CORRELATION))
                .willReturn(result);

        assertThatThrownBy(() -> reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        )).isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("menu hold fulfillment result is inconsistent");
        then(fulfillmentAuditRepository).shouldHaveNoInteractions();
    }

    private static Stream<ReservationMenuHoldResult> inconsistentFulfillmentHoldResults() {
        return Stream.of(
                null,
                ReservationMenuHoldResult.fulfilled(RESERVATION_ID + 1),
                ReservationMenuHoldResult.released(RESERVATION_ID)
        );
    }

    @Test
    void propagatesMenuHoldServiceExceptionWithoutReservationRemapping() {
        stubFreshConfirmed(ReservationMenuHoldTerminationPresence.HOLD_PRESENT);
        ServiceException failure = new ServiceException(
                MenuHoldErrorCode.INVENTORY_STATE_CONFLICT
        );
        given(menuHoldPort.fulfill(anyLong(), anyString())).willThrow(failure);

        assertThatThrownBy(() -> reservationService.fulfillStoreReservation(
                OPERATOR_ID,
                STORE_ID,
                RESERVATION_ID,
                fulfillmentCommand(),
                REQUESTED_AT,
                FULFILLMENT_CORRELATION
        )).isSameAs(failure);
        then(fulfillmentAuditRepository).shouldHaveNoInteractions();
    }

    private static Stream<Arguments> approvedSortCases() {
        return Stream.of(
                Arguments.of("createdAt,desc", "createdAt", Sort.Direction.DESC),
                Arguments.of("createdAt,asc", "createdAt", Sort.Direction.ASC),
                Arguments.of(
                        "serviceDate,desc",
                        "timeSnapshot.serviceDate",
                        Sort.Direction.DESC
                ),
                Arguments.of(
                        "serviceDate,asc",
                        "timeSnapshot.serviceDate",
                        Sort.Direction.ASC
                ),
                Arguments.of(
                        "startAt,desc",
                        "timeSnapshot.startAt",
                        Sort.Direction.DESC
                ),
                Arguments.of(
                        "startAt,asc",
                        "timeSnapshot.startAt",
                        Sort.Direction.ASC
                )
        );
    }

    private void assertValidationFailure(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(CommonErrorCode.VALIDATION_FAILED));
    }

    private void assertHiddenReservationNotFound(Runnable invocation) {
        assertThatThrownBy(invocation::run)
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode())
                                .isEqualTo(ReservationErrorCode.RESERVATION_NOT_FOUND));
    }

    private IdempotencyCommand fulfillmentCommand() {
        return new IdempotencyCommand(
                "store-operator",
                OPERATOR_ID,
                "RESERVATION_FULFILL",
                FULFILLMENT_KEY,
                "f".repeat(64)
        );
    }

    private Reservation confirmedReservation() {
        return reservation(RESERVATION_ID, 11L);
    }

    private Reservation reservationIn(ReservationStatus status) {
        Reservation reservation = confirmedReservation();
        switch (status) {
            case CONFIRMED -> {
            }
            case CANCELLED -> reservation.cancel(OCCURRED_AT);
            case FULFILLED -> reservation.fulfill(OCCURRED_AT);
        }
        return reservation;
    }

    private void stubFreshConfirmed(ReservationMenuHoldTerminationPresence presence) {
        stubFreshIdempotency(fulfillmentCommand(), null);
        given(reservationRepository.findByIdAndStoreIdForUpdate(
                RESERVATION_ID,
                STORE_ID
        )).willReturn(Optional.of(confirmedReservation()));
        given(menuHoldPort.lockForTermination(RESERVATION_ID)).willReturn(presence);
        lenient().when(fulfillmentAuditRepository.saveAndFlush(
                any(ReservationFulfillmentAudit.class)
        )).thenAnswer(invocation -> invocation.getArgument(0));
        if (presence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT) {
            lenient().when(menuHoldPort.findSnapshots(RESERVATION_ID))
                    .thenReturn(List.of());
        }
    }

    private IdempotentOutcome successfulFulfillmentOutcome() {
        Reservation replayed = confirmedReservation();
        replayed.fulfill(OCCURRED_AT);
        ReservationDetailResponse data = ReservationDetailResponse.from(replayed, List.of());
        return new IdempotentOutcome(
                true,
                200,
                "SUCCESS",
                "RESERVATION",
                String.valueOf(RESERVATION_ID),
                new ObjectMapper().valueToTree(data)
        );
    }

    private static void assertServiceError(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call)
                .isInstanceOf(ServiceException.class)
                .satisfies(error -> assertThat(((ServiceException) error).getErrorCode())
                        .isEqualTo(expected));
    }

    private IdempotencyCommand cancellationCommand(String namespace, long actorId) {
        return new IdempotencyCommand(
                namespace,
                actorId,
                "RESERVATION_CANCEL",
                CANCELLATION_KEY,
                "a".repeat(64)
        );
    }

    private void stubFreshIdempotency(
            IdempotencyCommand command,
            AtomicReference<BusinessResult<?>> capturedResult
    ) {
        given(idempotencyExecutor.execute(eq(command), any())).willAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            if (capturedResult != null) {
                capturedResult.set(result);
            }
            return new IdempotentOutcome(
                    false,
                    result.httpStatus(),
                    result.responseCode(),
                    result.resourceType(),
                    result.resourceId(),
                    new ObjectMapper().valueToTree(result.data())
            );
        });
    }

    private Reservation stubCancellationThroughAllocation(
            IdempotencyCommand command,
            ReservationMenuHoldTerminationPresence presence,
            AtomicReference<BusinessResult<?>> capturedResult
    ) {
        Reservation reservation = reservation(77L, 11L);
        stubFreshIdempotency(command, capturedResult);
        given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(reservation));
        given(cancellationPolicyEvaluator.evaluate(
                1L,
                ReservationCancellationActorType.CONSUMER,
                ReservationStatus.CONFIRMED,
                reservation.getStartAt(),
                NOW.minusSeconds(10)
        )).willReturn(ReservationCancellationDecision.ALLOWED);
        given(menuHoldPort.lockForTermination(77L)).willReturn(presence);
        given(capacityAllocationRepository
                .findAllByReservationIdOrderByCapacityBucketIdAsc(77L))
                .willReturn(List.of(
                        ReservationCapacityAllocation.allocate(77L, 301L, 3, 3L)
                ));
        return reservation;
    }

    private CancellationFixture stubSuccessfulCancellation(
            IdempotencyCommand command,
            ReservationMenuHoldTerminationPresence presence
    ) {
        return stubCancellation(command, presence, true);
    }

    private CancellationFixture stubCancellation(
            IdempotencyCommand command,
            ReservationMenuHoldTerminationPresence presence,
            boolean expectedToComplete
    ) {
        return stubCancellation(command, presence, expectedToComplete, null);
    }

    private CancellationFixture stubCancellation(
            IdempotencyCommand command,
            ReservationMenuHoldTerminationPresence presence,
            boolean expectedToComplete,
            AtomicReference<BusinessResult<?>> capturedResult
    ) {
        boolean storeOperator = "store-operator".equals(command.principalNamespace());
        ReservationCancellationActorType actorType = storeOperator
                ? ReservationCancellationActorType.STORE_OPERATOR
                : ReservationCancellationActorType.CONSUMER;
        long actorId = storeOperator ? 33L : 11L;
        String correlationId = storeOperator
                ? OPERATOR_CANCELLATION_CORRELATION
                : CONSUMER_CANCELLATION_CORRELATION;
        Reservation reservation = reservation(77L, 11L);
        ReservationCapacityAllocation firstAllocation =
                ReservationCapacityAllocation.allocate(77L, 301L, 3, 3L);
        ReservationCapacityAllocation secondAllocation =
                ReservationCapacityAllocation.allocate(77L, 302L, 3, 3L);
        ReservationCapacityBucket firstOriginal = cancellationBucket(
                301L, START_TIME, LocalTime.of(18, 30), 6, 2, 3L);
        ReservationCapacityBucket secondOriginal = cancellationBucket(
                302L, LocalTime.of(18, 30), LocalTime.of(19, 15), 6, 2, 3L);
        ReservationCapacityBucket current = cancellationBucket(
                401L, START_TIME, LocalTime.of(19, 15), 6, 2, 4L);
        List<ReservationCapacityBucket> lockedBuckets =
                List.of(firstOriginal, secondOriginal, current);

        stubFreshIdempotency(command, capturedResult);
        if (storeOperator) {
            given(reservationRepository.findByIdAndStoreIdForUpdate(77L, 22L))
                    .willReturn(Optional.of(reservation));
        } else {
            given(reservationRepository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                    .willReturn(Optional.of(reservation));
        }
        given(cancellationPolicyEvaluator.evaluate(
                1L,
                actorType,
                ReservationStatus.CONFIRMED,
                reservation.getStartAt(),
                NOW.minusSeconds(10)
        )).willReturn(ReservationCancellationDecision.ALLOWED);
        given(menuHoldPort.lockForTermination(77L)).willReturn(presence);
        given(capacityAllocationRepository
                .findAllByReservationIdOrderByCapacityBucketIdAsc(77L))
                .willReturn(List.of(firstAllocation, secondAllocation));
        given(capacityBucketRepository.findLatestPolicyVersion(22L, SERVICE_DATE))
                .willReturn(Optional.of(4L));
        given(capacityBucketRepository.findLatestPolicyBucketIdsOverlapping(
                List.of(22L),
                SERVICE_DATE,
                START_TIME,
                LocalTime.of(19, 15)
        )).willReturn(List.of(401L));
        given(capacityBucketRepository.findAllByIdInForUpdate(
                List.of(301L, 302L, 401L)))
                .willReturn(lockedBuckets);
        if (expectedToComplete && presence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT) {
            given(menuHoldPort.release(77L, correlationId))
                    .willReturn(ReservationMenuHoldResult.released(77L));
        }
        if (expectedToComplete) {
            lenient().when(reservationRepository.findById(77L))
                    .thenReturn(Optional.of(reservation));
            given(cancellationAuditRepository.saveAndFlush(
                    any(ReservationCancellationAudit.class)))
                    .willAnswer(invocation -> invocation.getArgument(0));
            if (presence == ReservationMenuHoldTerminationPresence.HOLD_PRESENT) {
                given(menuHoldPort.findSnapshots(77L)).willReturn(List.of());
            }
        }

        return new CancellationFixture(reservation, lockedBuckets);
    }

    private static ReservationCapacityBucket cancellationBucket(
            long id,
            LocalTime startTime,
            LocalTime endTime,
            int occupiedPeople,
            int occupiedTeams,
            long policyVersion
    ) {
        ReservationCapacityBucket bucket = ReservationCapacityBucket.create(
                22L,
                SERVICE_DATE,
                startTime,
                endTime,
                12,
                4,
                occupiedPeople,
                occupiedTeams,
                1,
                6,
                true,
                policyVersion
        );
        ReflectionTestUtils.setField(bucket, "id", id);
        return bucket;
    }

    private Object invokeConsumerCancellation(
            IdempotencyCommand command,
            String reason,
            Instant requestedAt,
            String correlationId
    ) {
        return invokeConsumerCancellation(
                77L, command, reason, requestedAt, correlationId);
    }

    private Object invokeConsumerCancellation(
            long reservationId,
            IdempotencyCommand command,
            String reason,
            Instant requestedAt,
            String correlationId
    ) {
        return invokeCancellation(
                "cancelConsumerReservation",
                new Class<?>[] {
                    long.class,
                    long.class,
                    IdempotencyCommand.class,
                    String.class,
                    Instant.class,
                    String.class
                },
                new Object[] {
                    11L, reservationId, command, reason, requestedAt, correlationId
                }
        );
    }

    private Object invokeStoreCancellation(
            IdempotencyCommand command,
            String reason,
            Instant requestedAt,
            String correlationId
    ) {
        return invokeStoreCancellation(
                77L, command, reason, requestedAt, correlationId);
    }

    private Object invokeStoreCancellation(
            long reservationId,
            IdempotencyCommand command,
            String reason,
            Instant requestedAt,
            String correlationId
    ) {
        return invokeCancellation(
                "cancelStoreReservation",
                new Class<?>[] {
                    long.class,
                    long.class,
                    long.class,
                    IdempotencyCommand.class,
                    String.class,
                    Instant.class,
                    String.class
                },
                new Object[] {
                    33L, 22L, reservationId, command, reason, requestedAt, correlationId
                }
        );
    }

    private Object invokeCancellation(
            String methodName,
            Class<?>[] parameterTypes,
            Object[] arguments
    ) {
        Method method = ReflectionUtils.findMethod(
                ReservationService.class,
                methodName,
                parameterTypes
        );
        assertThat(method)
                .as("service method %s must be present before cancellation is invoked", methodName)
                .isNotNull();
        try {
            return method.invoke(reservationService, arguments);
        } catch (InvocationTargetException exception) {
            Throwable cause = exception.getCause();
            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }
            if (cause instanceof Error error) {
                throw error;
            }
            throw new AssertionError("cancellation service invocation failed", cause);
        } catch (ReflectiveOperationException exception) {
            throw new AssertionError("cancellation service invocation failed", exception);
        }
    }

    private int cancellationHttpStatus(Object result) {
        Number status = ReflectionTestUtils.invokeMethod(result, "httpStatus");
        return status.intValue();
    }

    private ReservationDetailResponse cancellationData(Object result) {
        return ReflectionTestUtils.invokeMethod(result, "data");
    }

    private record CancellationFixture(
            Reservation reservation,
            List<ReservationCapacityBucket> lockedBuckets
    ) {
    }

    private Reservation reservation(Long reservationId, Long consumerAccountId) {
        ReservationTimePolicyVersion policy = ReservationTimePolicyVersion.createDraft(
                22L,
                4L,
                30,
                60,
                15
        );
        policy.activate(Instant.parse("2026-08-01T00:00:00Z"), "test policy");
        ReservationTimeSnapshot snapshot = ReservationTimeSnapshot.calculate(
                policy,
                LocalDateTime.of(2026, 8, 3, 18, 0),
                ZoneId.of("Asia/Seoul"),
                null
        );
        Reservation reservation = Reservation.confirm(
                consumerAccountId,
                22L,
                "미리윰 식당",
                snapshot,
                PartyComposition.of(2, 1, 0),
                ReservationContactSnapshot.contactable("consumer:11:channel:primary"),
                3L,
                new ReservationCancellationPolicyVersion(1L),
                Instant.parse("2026-08-01T09:00:00Z")
        );
        ReflectionTestUtils.setField(reservation, "id", reservationId);
        return reservation;
    }
}
