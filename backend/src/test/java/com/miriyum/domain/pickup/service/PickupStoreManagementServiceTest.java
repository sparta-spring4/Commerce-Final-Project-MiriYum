package com.miriyum.domain.pickup.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.BDDMockito.then;
import static org.mockito.Mockito.never;

import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.domain.pickup.dto.request.PickupStoreSearchRequest;
import com.miriyum.domain.pickup.dto.request.StorePickupCancellationRequest;
import com.miriyum.domain.pickup.dto.response.PickupReservationPageResponse;
import com.miriyum.domain.pickup.dto.response.PickupReservationResponse;
import com.miriyum.domain.pickup.entity.PickupItemSnapshot;
import com.miriyum.domain.pickup.entity.PickupReservation;
import com.miriyum.domain.pickup.entity.PickupStatus;
import com.miriyum.domain.pickup.repository.PickupReservationRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
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
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.test.util.ReflectionTestUtils;
import tools.jackson.databind.ObjectMapper;

@ExtendWith(MockitoExtension.class)
class PickupStoreManagementServiceTest {

    private static final Instant NOW = Instant.parse("2026-08-10T04:00:00Z");
    private static final IdempotencyKey KEY = IdempotencyKey.parse(
            "550e8400-e29b-41d4-a716-446655440000");

    @Mock StoreService storeService;
    @Mock MenuInventoryTransactionService inventoryService;
    @Mock PickupReservationRepository repository;
    @Mock IdempotencyExecutor idempotencyExecutor;

    private PickupStoreManagementService service;

    @BeforeEach
    void setUp() {
        service = new PickupStoreManagementService(
                storeService, inventoryService, repository, idempotencyExecutor,
                new ObjectMapper(), Clock.fixed(NOW, ZoneOffset.UTC));
        org.mockito.Mockito.lenient()
                .when(idempotencyExecutor.execute(any(), any())).thenAnswer(invocation -> {
                    Supplier<BusinessResult<?>> work = invocation.getArgument(1);
                    BusinessResult<?> result = work.get();
                    return new IdempotentOutcome(
                            false, result.httpStatus(), result.responseCode(),
                            result.resourceType(), result.resourceId(),
                            new ObjectMapper().valueToTree(result.data()));
                });
    }

    @Test
    void listsOnlyOwnedStoresPickupsWithValidatedFilters() {
        PickupStoreSearchRequest request = PickupStoreSearchRequest.from(
                LocalDate.of(2026, 8, 10), "CONFIRMED", 0, 20, "pickupDate,asc");
        given(repository.findAllByStoreIdAndPickupDateAndStatus(
                org.mockito.ArgumentMatchers.eq(22L),
                org.mockito.ArgumentMatchers.eq(LocalDate.of(2026, 8, 10)),
                org.mockito.ArgumentMatchers.eq(PickupStatus.CONFIRMED), any(Pageable.class)))
                .willReturn(new PageImpl<>(List.of(confirmedPickup())));
        given(repository.findAllWithItemsByIdIn(List.of(77L)))
                .willReturn(List.of(confirmedPickup()));

        PickupReservationPageResponse result = service.list(31L, 22L, request);

        assertThat(result.items()).singleElement()
                .satisfies(item -> assertThat(item.pickupReservationId()).isEqualTo("77"));
        assertThat(result.page().totalElements()).isEqualTo(1);
        then(storeService).should().requireManagementOwnership(31L, 22L);
        then(repository).should().findAllWithItemsByIdIn(List.of(77L));
    }

    @Test
    void getsDetailOnlyAfterOwnershipAndThroughStoreScopedQuery() {
        given(repository.findByIdAndStoreId(77L, 22L))
                .willReturn(Optional.of(confirmedPickup()));

        PickupReservationResponse result = service.getDetail(31L, 22L, 77L);

        assertThat(result.pickupReservationId()).isEqualTo("77");
        then(storeService).should().requireManagementOwnership(31L, 22L);
        then(repository).should().findByIdAndStoreId(77L, 22L);
        then(repository).should(never()).findById(any());
    }

    @Test
    void cancelsConfirmedPickupAfterPickupTimeAndRestoresOriginalAcquireOperation() {
        PickupReservation pickup = confirmedPickup();
        given(repository.findByIdAndStoreIdForUpdate(77L, 22L))
                .willReturn(Optional.of(pickup));
        given(inventoryService.restore(any())).willReturn(new MenuInventoryRestoreResult(
                "pickup-store-cancel-31-" + KEY.value(), "pickup-acquire-result"));
        given(repository.saveAndFlush(pickup)).willReturn(pickup);

        PickupCommandResult result = service.cancel(
                31L, 22L, 77L, KEY, new StorePickupCancellationRequest("재료 소진"));

        assertThat(result.data().status()).isEqualTo(PickupStatus.CANCELLED);
        assertThat(result.data().cancellationReason()).isEqualTo("재료 소진");
        ArgumentCaptor<MenuInventoryRestoreCommand> restore =
                ArgumentCaptor.forClass(MenuInventoryRestoreCommand.class);
        then(inventoryService).should().restore(restore.capture());
        assertThat(restore.getValue().sourceAcquireOperationId())
                .isEqualTo("pickup-acquire-result");
        then(storeService).should().requireManagementOwnership(31L, 22L);
    }

    @Test
    void fulfillsConfirmedPickupWithoutRestoringInventory() {
        PickupReservation pickup = confirmedPickup();
        given(repository.findByIdAndStoreIdForUpdate(77L, 22L))
                .willReturn(Optional.of(pickup));
        given(repository.saveAndFlush(pickup)).willReturn(pickup);

        PickupCommandResult result = service.fulfill(31L, 22L, 77L, KEY);

        assertThat(result.data().status()).isEqualTo(PickupStatus.PICKED_UP);
        assertThat(pickup.getPickedUpAt()).isEqualTo(NOW);
        then(inventoryService).shouldHaveNoInteractions();
    }

    @Test
    void acceptsStoreCancellationReasonWithFiveHundredUnicodeCodePoints() {
        String reason = "😀".repeat(500);
        PickupReservation pickup = confirmedPickup();
        given(repository.findByIdAndStoreIdForUpdate(77L, 22L))
                .willReturn(Optional.of(pickup));
        given(inventoryService.restore(any())).willReturn(new MenuInventoryRestoreResult(
                "pickup-store-cancel-31-" + KEY.value(), "pickup-acquire-result"));
        given(repository.saveAndFlush(pickup)).willReturn(pickup);

        PickupCommandResult result = service.cancel(
                31L, 22L, 77L, KEY, new StorePickupCancellationRequest(reason));

        assertThat(result.data().cancellationReason()).isEqualTo(reason);
    }

    @Test
    void stopsBeforeRepositoryWhenOperatorDoesNotOwnStore() {
        org.mockito.BDDMockito.willThrow(new ServiceException(StoreErrorCode.ACCESS_DENIED))
                .given(storeService).requireManagementOwnership(31L, 22L);

        ServiceException exception = catchThrowableOfType(
                ServiceException.class, () -> service.getDetail(31L, 22L, 77L));

        assertThat(exception.getErrorCode()).isEqualTo(StoreErrorCode.ACCESS_DENIED);
        then(repository).shouldHaveNoInteractions();
    }

    private static PickupReservation confirmedPickup() {
        PickupReservation pickup = PickupReservation.confirm(
                11L, 22L, "미리윰 강남점", "Asia/Seoul",
                LocalDate.of(2026, 8, 10), LocalTime.NOON,
                Instant.parse("2026-08-10T03:00:00Z"), "pickup-acquire-result",
                List.of(new PickupItemSnapshot(
                        33L, 44L, 5L, "바질 파스타", 12_000, 6L,
                        LocalDate.of(2026, 8, 10), LocalTime.NOON,
                        LocalDate.of(2026, 8, 10), LocalTime.of(14, 0), 2)),
                Instant.parse("2026-08-09T01:00:00Z"));
        ReflectionTestUtils.setField(pickup, "id", 77L);
        return pickup;
    }
}
