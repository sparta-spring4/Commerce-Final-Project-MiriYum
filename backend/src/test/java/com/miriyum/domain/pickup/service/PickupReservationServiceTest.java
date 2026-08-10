package com.miriyum.domain.pickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.miriyum.domain.auth.exception.AuthErrorCode;
import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireResult;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquiredItem;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability.AvailabilityStatus;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.domain.pickup.dto.request.PickupMenuSelectionRequest;
import com.miriyum.domain.pickup.dto.request.PickupReservationCreateRequest;
import com.miriyum.domain.pickup.dto.request.PickupCancellationRequest;
import com.miriyum.domain.pickup.entity.PickupItemSnapshot;
import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.exception.PickupErrorCode;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import com.miriyum.domain.store.dto.contract.StorePickupTransactionEligibility;
import com.miriyum.domain.store.service.StoreService;
import com.miriyum.domain.store.service.StoreTransactionEligibilityService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.global.idempotency.BusinessResult;
import com.miriyum.global.idempotency.IdempotencyExecutor;
import com.miriyum.global.idempotency.IdempotencyKey;
import com.miriyum.global.idempotency.IdempotentOutcome;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PickupReservationServiceTest {

    private static final LocalDate PICKUP_DATE = LocalDate.of(2026, 8, 10);
    private static final LocalTime PICKUP_TIME = LocalTime.NOON;
    private static final Instant NOW = Instant.parse("2026-08-09T01:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000");

    @Mock StoreTransactionEligibilityService storeTransactionEligibilityService;
    @Mock StoreService storeService;
    @Mock MenuInventoryTransactionService inventoryService;
    @Mock PickupReservationRepository repository;
    @Mock IdempotencyExecutor idempotencyExecutor;
    @Mock ConsumerAccountService consumerAccountService;
    private PickupIntervalTimePolicy intervalTimePolicy;

    private PickupReservationService service;

    @BeforeEach
    void setUp() {
        intervalTimePolicy = org.mockito.Mockito.spy(new PickupIntervalTimePolicy(
                Clock.fixed(NOW, ZoneOffset.UTC)));
        service = new PickupReservationService(
                storeTransactionEligibilityService, storeService, inventoryService,
                repository, idempotencyExecutor, consumerAccountService,
                intervalTimePolicy, new ObjectMapper(),
                Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient().doReturn(true).when(intervalTimePolicy)
                .isOpen(any(), any(), any());
        org.mockito.Mockito.lenient()
                .when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation -> {
            Supplier<BusinessResult<?>> work = invocation.getArgument(1);
            BusinessResult<?> result = work.get();
            return new IdempotentOutcome(false, result.httpStatus(), result.responseCode(),
                    result.resourceType(), result.resourceId(),
                    new ObjectMapper().valueToTree(result.data()));
                });
    }

    @Test
    void rejectsEndedAvailabilityBeforeInventoryAcquire() {
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(
                        22L, "미리윰 강남점", "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "바질 파스타", 12_000, true, true));
        given(inventoryService.findOnlineAvailabilityByDate(any()))
                .willReturn(List.of(availability(5)));
        given(intervalTimePolicy.isOpen(
                "Asia/Seoul", PICKUP_DATE, LocalTime.of(14, 0)))
                .willReturn(false);

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(1)));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.SLOT_NOT_AVAILABLE);
        then(inventoryService).should(never()).acquire(any());
        then(repository).shouldHaveNoInteractions();
    }

    @Test
    void rechecksIntervalImmediatelyBeforeInventoryAcquire() {
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(
                        22L, "미리윰 강남점", "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "바질 파스타", 12_000, true, true));
        given(inventoryService.findOnlineAvailabilityByDate(any()))
                .willReturn(List.of(availability(5)));
        org.mockito.BDDMockito.willThrow(
                        new ServiceException(PickupErrorCode.SLOT_NOT_AVAILABLE))
                .given(intervalTimePolicy).requireOpen(
                        "Asia/Seoul", PICKUP_DATE, LocalTime.of(14, 0));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(1)));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.SLOT_NOT_AVAILABLE);
        then(inventoryService).should(never()).acquire(any());
        then(repository).shouldHaveNoInteractions();
    }

    @Test
    void rejectsRestrictedConsumerBeforeCreationSideEffects() {
        org.mockito.BDDMockito.willThrow(
                        new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED))
                .given(consumerAccountService).requireActiveAccount(11L);

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(1)));

        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED);
        then(idempotencyExecutor).shouldHaveNoInteractions();
        then(repository).shouldHaveNoInteractions();
        then(inventoryService).shouldHaveNoInteractions();
        then(storeTransactionEligibilityService).shouldHaveNoInteractions();
    }

    @Test
    void mapsDuplicateMenuQuantityTotalOverOneHundredToValidationFailure() {
        PickupReservationCreateRequest request = new PickupReservationCreateRequest(
                "22", PICKUP_DATE, PICKUP_TIME,
                List.of(
                        new PickupMenuSelectionRequest("33", 60),
                        new PickupMenuSelectionRequest("33", 60)));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request));

        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.VALIDATION_FAILED);
        then(idempotencyExecutor).shouldHaveNoInteractions();
        then(storeTransactionEligibilityService).shouldHaveNoInteractions();
        then(inventoryService).shouldHaveNoInteractions();
    }

    @Test
    void rejectsRestrictedConsumerBeforeDetailRepositoryAccess() {
        org.mockito.BDDMockito.willThrow(
                        new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED))
                .given(consumerAccountService).requireActiveAccount(11L);

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.getConsumerPickup(11L, 77L));

        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED);
        then(repository).shouldHaveNoInteractions();
    }

    @Test
    void rejectsRestrictedConsumerBeforeCancellationSideEffects() {
        org.mockito.BDDMockito.willThrow(
                        new ServiceException(AuthErrorCode.ACCOUNT_RESTRICTED))
                .given(consumerAccountService).requireActiveAccount(11L);

        ServiceException exception = catchThrowableOfType(ServiceException.class, () ->
                service.cancelByConsumer(
                        11L, 77L, KEY, new PickupCancellationRequest("일정 변경"), NOW));

        assertThat(exception.getErrorCode()).isEqualTo(AuthErrorCode.ACCOUNT_RESTRICTED);
        then(idempotencyExecutor).shouldHaveNoInteractions();
        then(repository).shouldHaveNoInteractions();
        then(inventoryService).shouldHaveNoInteractions();
    }

    @Test
    void createsConfirmedPickupFromLockedStoreAndActualAcquiredBucket() {
        PickupReservationCreateRequest request = request(2);
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(22L, "미리윰 강남점", "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "바질 파스타", 12_000, true, true));
        given(inventoryService.findOnlineAvailabilityByDate(any()))
                .willReturn(List.of(availability(5)));
        given(inventoryService.acquire(any())).willReturn(new MenuInventoryAcquireResult(
                "pickup-acquire-result", List.of(new MenuInventoryAcquiredItem(44L, 33L, 6L, 2))));
        given(repository.saveAndFlush(any())).willAnswer(invocation -> {
            PickupReservation saved = invocation.getArgument(0);
            ReflectionTestUtils.setField(saved, "id", 77L);
            return saved;
        });

        PickupCommandResult result = service.create(11L, KEY, request);

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data().pickupReservationId()).isEqualTo("77");
        assertThat(result.data().storeId()).isEqualTo("22");
        assertThat(result.data().items()).singleElement().satisfies(item -> {
            assertThat(item.menuId()).isEqualTo("33");
            assertThat(item.menuName()).isEqualTo("바질 파스타");
            assertThat(item.unitPrice()).isEqualTo(12_000);
            assertThat(item.quantity()).isEqualTo(2);
        });
        ArgumentCaptor<MenuInventoryAcquireCommand> acquire =
                ArgumentCaptor.forClass(MenuInventoryAcquireCommand.class);
        then(inventoryService).should().acquire(acquire.capture());
        assertThat(acquire.getValue().selections()).singleElement().satisfies(selection -> {
            assertThat(selection.menuId()).isEqualTo(33L);
            assertThat(selection.serviceDate()).isEqualTo(PICKUP_DATE);
            assertThat(selection.startTime()).isEqualTo(PICKUP_TIME);
            assertThat(selection.endTime()).isEqualTo(LocalTime.of(14, 0));
            assertThat(selection.inventoryPolicyVersion()).isEqualTo(6L);
            assertThat(selection.quantity()).isEqualTo(2);
        });
        assertThat(acquire.getValue().operationId())
                .isEqualTo("pickup-create-11-" + KEY.value());
        ArgumentCaptor<PickupReservation> saved = ArgumentCaptor.forClass(PickupReservation.class);
        then(repository).should().saveAndFlush(saved.capture());
        assertThat(saved.getValue().getAcquireOperationId()).isEqualTo("pickup-acquire-result");
        assertThat(saved.getValue().getPickupAt()).isEqualTo(
                Instant.parse("2026-08-10T03:00:00Z"));
        assertThat(saved.getValue().getItems()).singleElement().satisfies(item -> {
            assertThat(item.getMenuInventoryBucketId()).isEqualTo(44L);
            assertThat(item.getServiceDate()).isEqualTo(PICKUP_DATE);
            assertThat(item.getEndTime()).isEqualTo(LocalTime.of(14, 0));
        });
    }

    @Test
    void rejectsMenuThatIsNotPickupEligibleBeforeInventoryAccess() {
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(22L, "미리윰 강남점", "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "바질 파스타", 12_000, true, false));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(1)));

        assertThat(exception.getErrorCode()).isEqualTo(StoreErrorCode.MENU_STATE_CONFLICT);
        then(inventoryService).shouldHaveNoInteractions();
        then(repository).shouldHaveNoInteractions();
    }

    @Test
    void mapsInventoryRaceToPickupQuantityError() {
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(22L, "미리윰 강남점", "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "바질 파스타", 12_000, true, true));
        given(inventoryService.findOnlineAvailabilityByDate(any()))
                .willReturn(List.of(availability(5)));
        given(inventoryService.acquire(any()))
                .willThrow(new ServiceException(MenuHoldErrorCode.INSUFFICIENT_QUANTITY));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(2)));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.INSUFFICIENT_QUANTITY);
        then(repository).should(never()).saveAndFlush(any());
    }

    @Test
    void mapsLockedStoreStateConflictToPickupEligibilityError() {
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willThrow(new ServiceException(StoreErrorCode.STORE_STATE_CONFLICT));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(1)));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.TRANSACTION_NOT_ELIGIBLE);
        then(storeService).shouldHaveNoInteractions();
        then(inventoryService).shouldHaveNoInteractions();
    }

    @Test
    void rejectsAvailabilityFromDifferentStoreTimeZone() {
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(22L, "미리윰 강남점", "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "바질 파스타", 12_000, true, true));
        given(inventoryService.findOnlineAvailabilityByDate(any()))
                .willReturn(List.of(new MenuInventoryAvailability(
                        33L, 6L, "Asia/Tokyo", PICKUP_DATE, PICKUP_TIME,
                        PICKUP_DATE, LocalTime.of(14, 0), 5, AvailabilityStatus.AVAILABLE)));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(1)));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.SLOT_NOT_AVAILABLE);
        then(inventoryService).should(never()).acquire(any());
    }

    @Test
    void rejectsMultipleCurrentIntervalsForSameMenuAndPickupTime() {
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(
                        22L, "MiriYum Gangnam", "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "Pasta", 12_000, true, true));
        given(inventoryService.findOnlineAvailabilityByDate(any()))
                .willReturn(List.of(
                        availability(5),
                        new MenuInventoryAvailability(
                                33L, 7L, "Asia/Seoul", PICKUP_DATE, PICKUP_TIME,
                                PICKUP_DATE, LocalTime.of(15, 0), 4,
                                AvailabilityStatus.AVAILABLE)
                ));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(1)));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.SLOT_NOT_AVAILABLE);
        then(inventoryService).should(never()).acquire(any());
        then(repository).shouldHaveNoInteractions();
    }

    @Test
    void rejectsDuplicateIntervalsWhenOnlyOneHasEnded() {
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(
                        22L, "MiriYum Gangnam", "Asia/Seoul"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "Pasta", 12_000, true, true));
        given(inventoryService.findOnlineAvailabilityByDate(any()))
                .willReturn(List.of(
                        new MenuInventoryAvailability(
                                33L, 6L, "Asia/Seoul", PICKUP_DATE, PICKUP_TIME,
                                PICKUP_DATE, LocalTime.of(13, 0), 5,
                                AvailabilityStatus.AVAILABLE),
                        availability(5)
                ));
        org.mockito.Mockito.lenient().when(intervalTimePolicy.isOpen(
                "Asia/Seoul", PICKUP_DATE, LocalTime.of(13, 0)))
                .thenReturn(false);
        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request(1)));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.SLOT_NOT_AVAILABLE);
        then(inventoryService).should(never()).acquire(any());
        then(repository).shouldHaveNoInteractions();
    }

    @ParameterizedTest
    @CsvSource({"2026-03-08,02:30", "2026-11-01,01:30"})
    void rejectsDstGapAndOverlapLocalPickupTimes(LocalDate date, LocalTime time) {
        PickupReservationCreateRequest request = new PickupReservationCreateRequest(
                "22", date, time,
                List.of(new PickupMenuSelectionRequest("33", 1)));
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(
                        22L, "뉴욕 픽업 매장", "America/New_York"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "바질 파스타", 12_000, true, true));
        org.mockito.Mockito.lenient()
                .when(inventoryService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(new MenuInventoryAvailability(
                        33L, 6L, "America/New_York", date, time,
                        date, time.plusHours(1), 5, AvailabilityStatus.AVAILABLE)));

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.SLOT_NOT_AVAILABLE);
        then(inventoryService).shouldHaveNoInteractions();
    }

    @Test
    void rejectsDstGapBeforeClassifyingSoldOutIntervalAsInsufficientQuantity() {
        LocalDate date = LocalDate.of(2026, 3, 8);
        LocalTime time = LocalTime.of(2, 30);
        PickupReservationCreateRequest request = new PickupReservationCreateRequest(
                "22", date, time,
                List.of(new PickupMenuSelectionRequest("33", 1)));
        given(storeTransactionEligibilityService.requirePickupTransactionEligibility(22L))
                .willReturn(new StorePickupTransactionEligibility(
                        22L, "뉴욕 픽업 매장", "America/New_York"));
        given(storeService.requireMenuTransactionEligibility(22L, 33L))
                .willReturn(new MenuTransactionEligibility(
                        22L, 33L, 5, "바질 파스타", 12_000, true, true));
        org.mockito.Mockito.lenient()
                .when(inventoryService.findOnlineAvailabilityByDate(any()))
                .thenReturn(List.of(new MenuInventoryAvailability(
                        33L, 6L, "America/New_York", date, time,
                        date, LocalTime.of(3, 30), 0, AvailabilityStatus.SOLD_OUT)));
        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.create(11L, KEY, request));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.SLOT_NOT_AVAILABLE);
        then(inventoryService).shouldHaveNoInteractions();
    }

    @Test
    void returnsStoredReplayWithoutRepeatingBusinessSideEffects() {
        PickupReservationResponse stored = new PickupReservationResponse(
                "77", "22", "미리윰 강남점", PICKUP_DATE, PICKUP_TIME,
                com.miriyum.domain.pickup.entity.PickupStatus.CONFIRMED,
                List.of(new com.miriyum.domain.pickup.dto.response.PickupReservationItemResponse(
                        "33", "바질 파스타", 12_000, 2)),
                null, null, java.time.OffsetDateTime.parse("2026-08-09T10:00:00+09:00"));
        org.mockito.Mockito.doReturn(new IdempotentOutcome(
                        true, 201, "SUCCESS", "pickup-reservation", "77",
                        new ObjectMapper().valueToTree(stored)))
                .when(idempotencyExecutor).execute(any(), any());

        PickupCommandResult result = service.create(11L, KEY, request(2));

        assertThat(result.httpStatus()).isEqualTo(201);
        assertThat(result.data()).isEqualTo(stored);
        then(consumerAccountService).should().requireActiveAccount(11L);
        then(storeTransactionEligibilityService).shouldHaveNoInteractions();
        then(storeService).shouldHaveNoInteractions();
        then(inventoryService).shouldHaveNoInteractions();
        then(repository).shouldHaveNoInteractions();
    }

    @Test
    void readsPickupOnlyThroughConsumerScopedRepositoryQuery() {
        given(repository.findByIdAndConsumerAccountId(77L, 11L))
                .willReturn(Optional.of(confirmedPickup()));

        PickupReservationResponse result = service.getConsumerPickup(11L, 77L);

        assertThat(result.pickupReservationId()).isEqualTo("77");
        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.menuName()).isEqualTo("바질 파스타"));
        then(repository).should().findByIdAndConsumerAccountId(77L, 11L);
        then(repository).should(never()).findById(any());
    }

    @Test
    void hidesMissingAndForeignConsumerPickupWithPickupNotFound() {
        given(repository.findByIdAndConsumerAccountId(77L, 11L))
                .willReturn(Optional.empty());

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.getConsumerPickup(11L, 77L));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.PICKUP_NOT_FOUND);
    }

    @Test
    void cancelsOwnedPickupAndRestoresItsStoredAcquireOperationOnce() {
        PickupReservation pickup = confirmedPickup();
        given(repository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(pickup));
        given(inventoryService.restore(any())).willReturn(new MenuInventoryRestoreResult(
                "pickup-cancel-11-" + KEY.value(), "pickup-acquire-result"));
        given(repository.saveAndFlush(pickup)).willReturn(pickup);

        PickupCommandResult result = service.cancelByConsumer(
                11L, 77L, KEY, new PickupCancellationRequest("일정 변경"), NOW);

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data().status())
                .isEqualTo(com.miriyum.domain.pickup.entity.PickupStatus.CANCELLED);
        assertThat(result.data().cancellationReason()).isEqualTo("일정 변경");
        ArgumentCaptor<MenuInventoryRestoreCommand> restore =
                ArgumentCaptor.forClass(MenuInventoryRestoreCommand.class);
        then(inventoryService).should().restore(restore.capture());
        assertThat(restore.getValue().sourceAcquireOperationId())
                .isEqualTo("pickup-acquire-result");
        assertThat(restore.getValue().operationId())
                .isEqualTo("pickup-cancel-11-" + KEY.value());
    }

    @Test
    void usesStableRequestedAtEvenWhenServiceClockHasReachedPickupTime() {
        Instant requestedAt = Instant.parse("2026-08-10T02:59:59Z");
        service = new PickupReservationService(
                storeTransactionEligibilityService, storeService, inventoryService,
                repository, idempotencyExecutor, consumerAccountService,
                intervalTimePolicy, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-10T03:00:01Z"), ZoneOffset.UTC));
        PickupReservation pickup = confirmedPickup();
        given(repository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(pickup));
        given(inventoryService.restore(any())).willReturn(new MenuInventoryRestoreResult(
                "pickup-cancel-11-" + KEY.value(), "pickup-acquire-result"));
        given(repository.saveAndFlush(pickup)).willReturn(pickup);

        PickupCommandResult result = service.cancelByConsumer(
                11L, 77L, KEY, new PickupCancellationRequest(null), requestedAt);

        assertThat(result.httpStatus()).isEqualTo(200);
        assertThat(result.data().status())
                .isEqualTo(com.miriyum.domain.pickup.entity.PickupStatus.CANCELLED);
    }

    @Test
    void rejectsConsumerCancellationExactlyAtPickupTimeBeforeRestore() {
        service = new PickupReservationService(
                storeTransactionEligibilityService, storeService, inventoryService,
                repository, idempotencyExecutor, consumerAccountService,
                intervalTimePolicy, new ObjectMapper(),
                Clock.fixed(Instant.parse("2026-08-10T03:00:00Z"), ZoneOffset.UTC));
        given(repository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(confirmedPickup()));

        ServiceException exception = catchThrowableOfType(ServiceException.class, () ->
                service.cancelByConsumer(
                        11L, 77L, KEY, new PickupCancellationRequest(null),
                        Instant.parse("2026-08-10T03:00:00Z")));

        assertThat(exception.getErrorCode()).isEqualTo(PickupErrorCode.CANCELLATION_NOT_ALLOWED);
        then(inventoryService).should(never()).restore(any());
    }

    @Test
    void acceptsConsumerCancellationReasonWithFiveHundredUnicodeCodePoints() {
        String reason = "😀".repeat(500);
        PickupReservation pickup = confirmedPickup();
        given(repository.findByIdAndConsumerAccountIdForUpdate(77L, 11L))
                .willReturn(Optional.of(pickup));
        given(inventoryService.restore(any())).willReturn(new MenuInventoryRestoreResult(
                "pickup-cancel-11-" + KEY.value(), "pickup-acquire-result"));
        given(repository.saveAndFlush(pickup)).willReturn(pickup);

        PickupCommandResult result = service.cancelByConsumer(
                11L, 77L, KEY, new PickupCancellationRequest(reason), NOW);

        assertThat(result.data().cancellationReason()).isEqualTo(reason);
    }

    private static PickupReservationCreateRequest request(int quantity) {
        return new PickupReservationCreateRequest(
                "22", PICKUP_DATE, PICKUP_TIME,
                List.of(new PickupMenuSelectionRequest("33", quantity)));
    }

    private static MenuInventoryAvailability availability(int availableQuantity) {
        return new MenuInventoryAvailability(
                33L, 6L, "Asia/Seoul", PICKUP_DATE, PICKUP_TIME,
                PICKUP_DATE, LocalTime.of(14, 0), availableQuantity,
                AvailabilityStatus.AVAILABLE);
    }

    private static PickupReservation confirmedPickup() {
        PickupReservation pickup = PickupReservation.confirm(
                11L, 22L, "미리윰 강남점", "Asia/Seoul",
                PICKUP_DATE, PICKUP_TIME, Instant.parse("2026-08-10T03:00:00Z"),
                "pickup-acquire-result",
                List.of(new PickupItemSnapshot(
                        33L, 44L, 5L, "바질 파스타", 12_000, 6L,
                        PICKUP_DATE, PICKUP_TIME, PICKUP_DATE,
                        LocalTime.of(14, 0), 2)), NOW);
        ReflectionTestUtils.setField(pickup, "id", 77L);
        return pickup;
    }
}
