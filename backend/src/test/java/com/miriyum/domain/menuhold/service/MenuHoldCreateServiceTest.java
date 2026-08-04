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
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalResult;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalStatus;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class MenuHoldCreateServiceTest {

    @Mock StoreService storeService;
    @Mock StoreServiceIntervalValidationService intervalService;
    @Mock MenuInventoryService inventoryService;
    @Mock MenuHoldRepository holdRepository;

    @Test
    void createsConfirmedHoldAfterEligibilityIntervalAndInventorySucceed() {
        MenuHoldCreateCommand command = command(List.of(new MenuSelection("40", 2)));
        given(storeService.requireMenuTransactionEligibility(20L, 40L))
                .willReturn(new MenuTransactionEligibility(20L, 40L, 7, true, false));
        CurrentInventorySelection inventory = new CurrentInventorySelection(
                40L, 50L, 3L, "Asia/Seoul", command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), 2);
        given(inventoryService.loadCurrentSelections(
                List.of(new MenuSelection("40", 2)), command.serviceDate(), command.startTime(),
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

        assertThat(result).isEqualTo(MenuHoldCommandResult.confirmed("10"));
    }

    @Test
    void rejectsMalformedServiceIntervalResultBeforeInventoryAcquisition() {
        MenuHoldCreateCommand command = command(List.of(new MenuSelection("40", 2)));
        given(storeService.requireMenuTransactionEligibility(20L, 40L))
                .willReturn(new MenuTransactionEligibility(20L, 40L, 7, true, false));
        CurrentInventorySelection inventory = new CurrentInventorySelection(
                40L, 50L, 3L, "Asia/Seoul", command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), 2);
        given(inventoryService.loadCurrentSelections(
                List.of(new MenuSelection("40", 2)), command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime())).willReturn(List.of(inventory));
        given(intervalService.validateServiceIntervals(org.mockito.ArgumentMatchers.anyList()))
                .willReturn(List.of());

        assertThatThrownBy(() -> service().create(command))
                .isInstanceOf(ServiceException.class)
                .extracting(error -> ((ServiceException) error).getErrorCode())
                .isEqualTo(MenuHoldErrorCode.INELIGIBLE_MENU);
    }

    private MenuHoldServiceRuntime service() {
        return new MenuHoldServiceRuntime(
                storeService, intervalService, inventoryService, holdRepository);
    }

    private static MenuHoldCreateCommand command(List<MenuSelection> selections) {
        return new MenuHoldCreateCommand(
                "10", "20", "30", LocalDate.of(2026, 8, 10), LocalTime.NOON,
                LocalDate.of(2026, 8, 10), LocalTime.of(13, 0), "operation-1", selections);
    }
}
