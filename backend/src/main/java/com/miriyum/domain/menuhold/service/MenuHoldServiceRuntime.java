package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuHoldCommandResult;
import com.miriyum.domain.menuhold.dto.MenuHoldCreateCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldFulfillCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldReleaseCommand;
import com.miriyum.domain.menuhold.dto.MenuHoldTerminationPresence;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldItemSnapshot;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.CurrentInventorySelection;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.store.core.service.StoreService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.domain.store.menu.dto.MenuTransactionEligibility;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalRequest;
import com.miriyum.domain.store.schedule.dto.StoreServiceIntervalStatus;
import com.miriyum.domain.store.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.global.exception.ServiceException;
import java.time.DateTimeException;
import java.time.ZoneId;
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

/** 일반 예약의 메뉴 홀드 생성·종결 선잠금·해제·이행 완료 런타임이다. */
@Service
@RequiredArgsConstructor
public class MenuHoldServiceRuntime implements MenuHoldService {

    private final StoreService storeService;
    private final StoreServiceIntervalValidationService intervalService;
    private final MenuInventoryService inventoryService;
    private final MenuHoldRepository holdRepository;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MenuHoldTerminationPresence lockForTermination(long reservationId) {
        if (holdRepository.findByReservationIdForUpdate(reservationId).isPresent()) {
            return MenuHoldTerminationPresence.HOLD_PRESENT;
        }
        return MenuHoldTerminationPresence.NO_HOLD;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    @Override
    public MenuHoldCommandResult create(MenuHoldCreateCommand command) {
        if (command.menuSelections().isEmpty()) {
            return MenuHoldCommandResult.noHold(command.reservationId());
        }
        long reservationId = command.reservationId();
        long storeId = command.storeId();
        long consumerId = command.consumerAccountId();
        List<MenuSelection> selections = command.menuSelections().stream()
                .sorted(Comparator.comparingLong(MenuSelection::menuId))
                .toList();
        if (holdRepository.existsByReservationId(reservationId)
                || holdRepository.existsByAcquireOperationId(command.operationId())) {
            throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }

        Map<Long, MenuTransactionEligibility> eligibilityByMenuId = new HashMap<>();
        for (MenuSelection selection : selections) {
            long menuId = selection.menuId();
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
            eligibilityByMenuId.put(menuId, eligibility);
        }

        List<CurrentInventorySelection> current = inventoryService.loadCurrentSelections(
                selections, command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime());
        if (current.stream().anyMatch(selection ->
                !matchesResolvedInterval(command, selection))) {
            throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
        }
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
        List<CurrentInventorySelection> acquired;
        try {
            acquired = inventoryService.acquireCurrentInventory(command.operationId(), current);
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, "uk_menu_inventory_ledger_operation_pool")) {
                throw conflictCausedBy(exception);
            }
            throw exception;
        }
        MenuHold hold = MenuHold.confirmed(
                reservationId, storeId, consumerId, command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime(), command.operationId(), acquired.stream()
                        .map(selection -> new MenuHoldItemSnapshot(
                                selection.menuId(), selection.bucketId(),
                                eligibilityByMenuId.get(selection.menuId())
                                        .publishedVersionNumber(),
                                eligibilityByMenuId.get(selection.menuId()).menuName(),
                                eligibilityByMenuId.get(selection.menuId()).unitPrice(),
                                selection.inventoryPolicyVersion(), selection.quantity()))
                        .toList());
        try {
            holdRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, "uk_menu_holds_reservation")
                    || containsConstraint(exception, "uk_menu_holds_acquire_operation")) {
                throw conflictCausedBy(exception);
            }
            throw exception;
        }
        return MenuHoldCommandResult.confirmed(command.reservationId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MenuHoldCommandResult release(MenuHoldReleaseCommand command) {
        MenuHold hold = findLockedHold(command.reservationId());
        boolean transitioned;
        try {
            transitioned = hold.release();
        } catch (IllegalStateException exception) {
            throw stateConflict(exception);
        }
        if (transitioned) {
            inventoryService.restoreInventory(new InventoryRestoreRequest(
                    command.operationId(), hold.getAcquireOperationId()));
        }
        return MenuHoldCommandResult.released(command.reservationId());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MenuHoldCommandResult fulfill(MenuHoldFulfillCommand command) {
        MenuHold hold = findLockedHold(command.reservationId());
        try {
            hold.fulfill();
        } catch (IllegalStateException exception) {
            throw stateConflict(exception);
        }
        return MenuHoldCommandResult.fulfilled(command.reservationId());
    }

    private MenuHold findLockedHold(long reservationId) {
        return holdRepository.findByReservationIdForUpdate(reservationId)
                .orElseThrow(() -> new ServiceException(
                        MenuHoldErrorCode.INVENTORY_STATE_CONFLICT));
    }

    private static ServiceException stateConflict(IllegalStateException cause) {
        ServiceException conflict =
                new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        conflict.initCause(cause);
        return conflict;
    }

    private static boolean matchesResolvedInterval(
            MenuHoldCreateCommand command,
            CurrentInventorySelection selection
    ) {
        try {
            ZoneId zone = ZoneId.of(selection.timeZoneId());
            var localStart = command.startAt().atZone(zone);
            var localEnd = command.serviceEndAt().atZone(zone);
            return localStart.toLocalDate().equals(selection.serviceDate())
                    && localStart.toLocalTime().equals(selection.startTime())
                    && localEnd.toLocalDate().equals(selection.endDate())
                    && localEnd.toLocalTime().equals(selection.endTime());
        } catch (DateTimeException exception) {
            return false;
        }
    }

    private static ServiceException conflictCausedBy(
            DataIntegrityViolationException exception
    ) {
        ServiceException conflict =
                new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        conflict.initCause(exception);
        return conflict;
    }

    private static boolean containsConstraint(Throwable failure, String marker) {
        Throwable current = failure;
        while (current != null) {
            if (current.getMessage() != null && current.getMessage().contains(marker)) {
                return true;
            }
            current = current.getCause();
        }
        return false;
    }

}
