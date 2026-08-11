package com.miriyum.domain.menuhold.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.inventory.dto.CurrentInventorySelection;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.global.exception.ServiceException;
import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.menu.service.MenuTransactionFacade;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalResult;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalStatus;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;

@ExtendWith(MockitoExtension.class)
class MenuHoldCreateServiceTest {

    @Mock MenuTransactionFacade menuTransactionFacade;
    @Mock StoreServiceIntervalValidationService intervalService;
    @Mock MenuInventoryService inventoryService;
    @Mock MenuHoldRepository holdRepository;

    @Test
    void createsConfirmedHoldAfterEligibilityIntervalAndInventorySucceed() {
        MenuHoldCreateCommand command = command(List.of(new MenuSelection(40L, 2)));
        given(menuTransactionFacade.requireTransactionEligibility(20L, 40L))
                .willReturn(eligibility());
        CurrentInventorySelection inventory = new CurrentInventorySelection(
                40L, 50L, 3L, "Asia/Seoul", command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), 2);
        given(inventoryService.loadCurrentSelections(
                List.of(new MenuSelection(40L, 2)), command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime())).willReturn(List.of(inventory));
        given(intervalService.validateServiceIntervals(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(List.of(new StoreServiceIntervalResult(
                        20L, Instant.parse("2026-08-10T03:00:00Z"),
                        Instant.parse("2026-08-10T04:00:00Z"),
                        StoreServiceIntervalStatus.ACCEPTING)));
        given(inventoryService.acquireCurrentInventory(command.operationId(), List.of(inventory)))
                .willReturn(List.of(inventory));
        given(holdRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        MenuHoldCommandResult result = service().create(command);

        assertThat(result).isEqualTo(MenuHoldCommandResult.confirmed(10L));
    }

    @Test
    void rejectsMalformedServiceIntervalResultBeforeInventoryAcquisition() {
        MenuHoldCreateCommand command = command(List.of(new MenuSelection(40L, 2)));
        given(menuTransactionFacade.requireTransactionEligibility(20L, 40L))
                .willReturn(eligibility());
        CurrentInventorySelection inventory = new CurrentInventorySelection(
                40L, 50L, 3L, "Asia/Seoul", command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), 2);
        given(inventoryService.loadCurrentSelections(
                List.of(new MenuSelection(40L, 2)), command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime())).willReturn(List.of(inventory));
        given(intervalService.validateServiceIntervals(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(List.of());

        assertThatThrownBy(() -> service().create(command))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INELIGIBLE_MENU);
    }

    @Test
    void rejectsAcceptingServiceIntervalResultForDifferentRequest() {
        MenuHoldCreateCommand command = command(List.of(new MenuSelection(40L, 2)));
        given(menuTransactionFacade.requireTransactionEligibility(20L, 40L))
                .willReturn(eligibility());
        CurrentInventorySelection inventory = new CurrentInventorySelection(
                40L, 50L, 3L, "Asia/Seoul", command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), 2);
        given(inventoryService.loadCurrentSelections(
                List.of(new MenuSelection(40L, 2)), command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime())).willReturn(List.of(inventory));
        given(intervalService.validateServiceIntervals(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(List.of(new StoreServiceIntervalResult(
                        999L, command.startAt(), command.serviceEndAt(),
                        StoreServiceIntervalStatus.ACCEPTING)));

        assertThatThrownBy(() -> service().create(command))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INELIGIBLE_MENU);
    }

    @Test
    void validatesTheExactInstantSelectedForDstOverlap() {
        MenuHoldCreateCommand command = new MenuHoldCreateCommand(
                10L, 20L, 30L, LocalDate.of(2026, 11, 1), LocalTime.of(1, 30),
                LocalDate.of(2026, 11, 1), LocalTime.of(2, 30),
                Instant.parse("2026-11-01T06:30:00Z"),
                Instant.parse("2026-11-01T07:30:00Z"),
                "operation-dst-overlap", List.of(new MenuSelection(40L, 1)));
        given(menuTransactionFacade.requireTransactionEligibility(20L, 40L))
                .willReturn(eligibility());
        CurrentInventorySelection inventory = new CurrentInventorySelection(
                40L, 50L, 3L, "America/New_York", command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), 1);
        given(inventoryService.loadCurrentSelections(
                command.menuSelections(), command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime())).willReturn(List.of(inventory));
        StoreServiceIntervalRequest exactRequest = new StoreServiceIntervalRequest(
                20L, command.startAt(), command.serviceEndAt());
        given(intervalService.validateServiceIntervals(List.of(exactRequest)))
                .willReturn(List.of(StoreServiceIntervalResult.of(exactRequest, true)));
        given(inventoryService.acquireCurrentInventory(command.operationId(), List.of(inventory)))
                .willReturn(List.of(inventory));
        given(holdRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .willAnswer(invocation -> invocation.getArgument(0));

        assertThat(service().create(command))
                .isEqualTo(MenuHoldCommandResult.confirmed(command.reservationId()));
    }

    @Test
    void rejectsInventoryIntervalThatDoesNotMatchResolvedInstants() {
        MenuHoldCreateCommand command = command(List.of(new MenuSelection(40L, 2)));
        given(menuTransactionFacade.requireTransactionEligibility(20L, 40L))
                .willReturn(eligibility());
        CurrentInventorySelection mismatchedInventory = new CurrentInventorySelection(
                40L, 50L, 3L, "Asia/Seoul",
                LocalDate.of(2026, 8, 10), LocalTime.of(18, 0),
                LocalDate.of(2026, 8, 10), LocalTime.of(19, 0), 2);
        given(inventoryService.loadCurrentSelections(
                command.menuSelections(), command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime()))
                .willReturn(List.of(mismatchedInventory));

        assertThatThrownBy(() -> service().create(command))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INELIGIBLE_MENU);
    }

    @Test
    void propagatesUnexpectedDataIntegrityViolationWithoutMaskingIt() {
        MenuHoldCreateCommand command = command(List.of(new MenuSelection(40L, 2)));
        prepareSuccessfulDependencies(command);
        DataIntegrityViolationException failure =
                new DataIntegrityViolationException("fk_menu_holds_reservation");
        given(holdRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .willThrow(failure);

        assertThatThrownBy(() -> service().create(command)).isSameAs(failure);
    }

    @Test
    void mapsKnownDuplicateConstraintToInventoryStateConflict() {
        MenuHoldCreateCommand command = command(List.of(new MenuSelection(40L, 2)));
        prepareSuccessfulDependencies(command);
        given(holdRepository.saveAndFlush(org.mockito.ArgumentMatchers.any()))
                .willThrow(new DataIntegrityViolationException(
                        "Duplicate entry for key 'uk_menu_holds_reservation'"));

        assertThatThrownBy(() -> service().create(command))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
    }

    private CurrentInventorySelection prepareSuccessfulDependencies(
            MenuHoldCreateCommand command
    ) {
        given(menuTransactionFacade.requireTransactionEligibility(20L, 40L))
                .willReturn(eligibility());
        CurrentInventorySelection inventory = new CurrentInventorySelection(
                40L, 50L, 3L, "Asia/Seoul", command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), 2);
        given(inventoryService.loadCurrentSelections(
                command.menuSelections(), command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime())).willReturn(List.of(inventory));
        StoreServiceIntervalRequest request = new StoreServiceIntervalRequest(
                20L, command.startAt(), command.serviceEndAt());
        given(intervalService.validateServiceIntervals(List.of(request)))
                .willReturn(List.of(StoreServiceIntervalResult.of(request, true)));
        given(inventoryService.acquireCurrentInventory(command.operationId(), List.of(inventory)))
                .willReturn(List.of(inventory));
        return inventory;
    }

    private MenuHoldServiceRuntime service() {
        return new MenuHoldServiceRuntime(
                menuTransactionFacade, intervalService, inventoryService, holdRepository);
    }

    private static MenuTransactionEligibility eligibility() {
        return new MenuTransactionEligibility(
                20L, 40L, 7, "아메리카노", 5_000, true, false);
    }

    private static MenuHoldCreateCommand command(List<MenuSelection> selections) {
        return new MenuHoldCreateCommand(
                10L, 20L, 30L, LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0),
                Instant.parse("2026-08-10T03:00:00Z"),
                Instant.parse("2026-08-10T04:00:00Z"),
                "operation-1", selections);
    }
}
