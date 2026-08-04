package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldItemSnapshot;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.CurrentInventorySelection;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalStatus;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.ServiceException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** #44-B의 메뉴 홀드 생성 런타임이다. */
@Service
@RequiredArgsConstructor
public class MenuHoldServiceRuntime {

    private final StoreService storeService;
    private final StoreServiceIntervalValidationService intervalService;
    private final MenuInventoryService inventoryService;
    private final MenuHoldRepository holdRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public MenuHoldCommandResult create(MenuHoldCreateCommand command) {
        if (command.menuSelections().isEmpty()) {
            return MenuHoldCommandResult.noHold(command.reservationId());
        }
        long reservationId = parseId(command.reservationId());
        long storeId = parseId(command.storeId());
        long consumerId = parseId(command.consumerAccountId());
        List<MenuSelection> selections = command.menuSelections().stream()
                .sorted(Comparator.comparingLong(selection -> parseId(selection.menuId())))
                .toList();
        if (holdRepository.existsByReservationId(reservationId)
                || holdRepository.existsByAcquireOperationId(command.operationId())) {
            throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }

        Map<Long, Long> menuVersions = new HashMap<>();
        for (MenuSelection selection : selections) {
            long menuId = parseId(selection.menuId());
            MenuTransactionEligibility eligibility;
            try {
                eligibility = storeService.requireMenuTransactionEligibility(storeId, menuId);
            } catch (ServiceException exception) {
                if (exception.getErrorCode() == StoreErrorCode.MENU_STATE_CONFLICT) {
                    throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
                }
                throw exception;
            }
            if (!eligibility.menuHoldEligible()) {
                throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
            }
            menuVersions.put(menuId, (long) eligibility.publishedVersionNumber());
        }

        List<CurrentInventorySelection> current = inventoryService.loadCurrentSelections(
                selections, command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime());
        List<StoreServiceIntervalRequest> intervalRequests = current.stream()
                .map(selection -> new StoreServiceIntervalRequest(
                        storeId,
                        command.startAt(),
                        command.serviceEndAt()))
                .toList();
        var intervalResults = intervalService.validateServiceIntervals(intervalRequests);
        if (intervalResults == null
                || intervalResults.size() != intervalRequests.size()
                || !IntStream.range(0, intervalRequests.size()).allMatch(index -> {
                    var request = intervalRequests.get(index);
                    var result = intervalResults.get(index);
                    return result != null
                            && result.storeId() == request.storeId()
                            && Objects.equals(result.startAt(), request.startAt())
                            && Objects.equals(result.serviceEndAt(), request.serviceEndAt())
                            && result.status() == StoreServiceIntervalStatus.ACCEPTING;
                })) {
            throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
        }
        List<CurrentInventorySelection> acquired =
                inventoryService.acquireCurrentInventory(command.operationId(), current);
        MenuHold hold = MenuHold.confirmed(
                reservationId, storeId, consumerId, command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), command.operationId(), acquired.stream()
                        .map(selection -> new MenuHoldItemSnapshot(
                                selection.menuId(), selection.bucketId(),
                                menuVersions.get(selection.menuId()),
                                selection.inventoryPolicyVersion(), selection.quantity()))
                        .toList());
        try {
            holdRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException exception) {
            throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
        return MenuHoldCommandResult.confirmed(command.reservationId());
    }

    private static long parseId(String value) {
        try {
            long id = Long.parseLong(value);
            if (id <= 0) {
                throw new NumberFormatException();
            }
            return id;
        } catch (NumberFormatException exception) {
            throw new IllegalArgumentException("public ID must be a positive BIGINT", exception);
        }
    }
}
