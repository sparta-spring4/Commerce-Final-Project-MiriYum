package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menu.dto.contract.MenuTransactionEligibility;
import com.miriyum.domain.menu.service.MenuTransactionFacade;
import com.miriyum.domain.menuhold.dto.MenuSelection;
import com.miriyum.domain.menuhold.dto.TemporaryMenuHoldContracts;
import com.miriyum.domain.menuhold.entity.MenuHold;
import com.miriyum.domain.menuhold.entity.MenuHoldItem;
import com.miriyum.domain.menuhold.entity.MenuHoldItemSnapshot;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.CurrentInventorySelection;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.repository.MenuHoldRepository;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalRequest;
import com.miriyum.domain.schedule.dto.contract.StoreServiceIntervalStatus;
import com.miriyum.domain.schedule.service.StoreServiceIntervalValidationService;
import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.stream.IntStream;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 임시 MenuHold의 replay·생성·루트 선잠금·종결 적용 런타임이다. */
@Service
@RequiredArgsConstructor
public class TemporaryMenuHoldServiceRuntime implements TemporaryMenuHoldService {

    private static final String ACQUIRE_PREFIX = "reservation-temp-menu-acquire:";
    private static final String RESTORE_PREFIX = "reservation-temp-menu-restore:";

    private final MenuTransactionFacade menuTransactionFacade;
    private final StoreServiceIntervalValidationService intervalService;
    private final MenuInventoryService inventoryService;
    private final MenuHoldRepository holdRepository;
    private final MenuHoldTerminalService terminalService;

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TemporaryMenuHoldContracts.Result verifyCreationReplay(
            TemporaryMenuHoldContracts.Replay command
    ) {
        return holdRepository.findByReservationHoldId(command.reservationHoldId())
                .map(hold -> verifyPresentReplay(hold, command.selections()))
                .orElseGet(() -> {
                    if (command.selections().isEmpty()) {
                        return noHold();
                    }
                    throw idempotencyReuse();
                });
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TemporaryMenuHoldContracts.Result create(TemporaryMenuHoldContracts.Create command) {
        if (command.selections().isEmpty()) {
            return noHold();
        }
        String acquireOperationId = ACQUIRE_PREFIX + command.reservationHoldId();
        if (holdRepository.findByReservationHoldId(command.reservationHoldId()).isPresent()
                || holdRepository.existsByAcquireOperationId(acquireOperationId)) {
            throw idempotencyReuse();
        }

        List<MenuSelection> selections = command.selections().stream()
                .map(selection -> new MenuSelection(selection.menuId(), selection.quantity()))
                .toList();
        Map<Long, MenuTransactionEligibility> eligibilityByMenuId = new HashMap<>();
        for (MenuSelection selection : selections) {
            MenuTransactionEligibility eligibility = requireEligibility(
                    command.storeId(), selection.menuId());
            eligibilityByMenuId.put(selection.menuId(), eligibility);
        }

        List<CurrentInventorySelection> current = inventoryService.loadCurrentSelections(
                selections, command.serviceDate(), command.startTime(),
                command.endDate(), command.endTime());
        if (current.stream().anyMatch(selection ->
                !matchesResolvedInterval(command, selection))) {
            throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
        }
        validateServiceIntervals(command, current.size());

        List<CurrentInventorySelection> acquired;
        try {
            acquired = inventoryService.acquireCurrentInventory(acquireOperationId, current);
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, "uk_menu_inventory_ledger_operation_pool")) {
                throw idempotencyReuse(exception);
            }
            throw exception;
        }

        MenuHold hold = MenuHold.temporaryActive(
                command.reservationHoldId(), command.storeId(), command.consumerAccountId(),
                command.serviceDate(), command.startTime(), command.endDate(), command.endTime(),
                command.expiresAt(), acquireOperationId,
                acquired.stream().map(selection -> {
                    MenuTransactionEligibility eligibility =
                            eligibilityByMenuId.get(selection.menuId());
                    return new MenuHoldItemSnapshot(
                            selection.menuId(), selection.bucketId(),
                            eligibility.publishedVersionNumber(), eligibility.menuName(),
                            eligibility.unitPrice(), selection.inventoryPolicyVersion(),
                            selection.quantity());
                }).toList());
        try {
            holdRepository.saveAndFlush(hold);
        } catch (DataIntegrityViolationException exception) {
            if (containsConstraint(exception, "uk_menu_holds_reservation_hold")
                    || containsConstraint(exception, "uk_menu_holds_acquire_operation")) {
                throw idempotencyReuse(exception);
            }
            throw exception;
        }
        return toResult(hold);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TemporaryMenuHoldContracts.Result lockForTransition(long reservationHoldId) {
        if (reservationHoldId <= 0) {
            throw new IllegalArgumentException("reservationHoldId must be positive");
        }
        return holdRepository.findByReservationHoldIdForUpdate(reservationHoldId)
                .map(TemporaryMenuHoldServiceRuntime::toResult)
                .orElseGet(TemporaryMenuHoldServiceRuntime::noHold);
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public TemporaryMenuHoldContracts.Result applyTransition(
            TemporaryMenuHoldContracts.ApplyTransition command
    ) {
        MenuHold hold = holdRepository.findByReservationHoldId(command.reservationHoldId())
                .orElseThrow(() -> new ServiceException(
                        MenuHoldErrorCode.INVENTORY_STATE_CONFLICT));
        boolean transitioned = terminalService.apply(
                hold, command.target(), command.finalReservationId());
        if (transitioned && (command.target() == TemporaryMenuHoldContracts.Target.RELEASE
                || command.target() == TemporaryMenuHoldContracts.Target.EXPIRE)) {
            inventoryService.restoreInventory(new InventoryRestoreRequest(
                    deriveRestoreOperationId(command.operationId()),
                    hold.getAcquireOperationId()));
        }
        return toResult(hold);
    }

    private TemporaryMenuHoldContracts.Result verifyPresentReplay(
            MenuHold hold,
            List<TemporaryMenuHoldContracts.Selection> expected
    ) {
        if (expected.isEmpty() || !canonicalPersistedSelections(hold).equals(expected)) {
            throw idempotencyReuse();
        }
        return toResult(hold);
    }

    private static List<TemporaryMenuHoldContracts.Selection> canonicalPersistedSelections(
            MenuHold hold
    ) {
        TreeMap<Long, Integer> quantities = new TreeMap<>();
        try {
            for (MenuHoldItem item : hold.getItems()) {
                quantities.merge(item.getMenuId(), item.getQuantity(), Math::addExact);
            }
        } catch (ArithmeticException exception) {
            throw idempotencyReuse(exception);
        }
        return quantities.entrySet().stream()
                .map(entry -> new TemporaryMenuHoldContracts.Selection(
                        entry.getKey(), entry.getValue()))
                .toList();
    }

    private MenuTransactionEligibility requireEligibility(long storeId, long menuId) {
        try {
            MenuTransactionEligibility eligibility =
                    menuTransactionFacade.requireTransactionEligibility(storeId, menuId);
            if (!eligibility.menuHoldEligible()) {
                throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
            }
            return eligibility;
        } catch (ServiceException exception) {
            if (exception.getErrorCode() == StoreErrorCode.MENU_STATE_CONFLICT) {
                throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
            }
            throw exception;
        }
    }

    private void validateServiceIntervals(
            TemporaryMenuHoldContracts.Create command,
            int selectionCount
    ) {
        List<StoreServiceIntervalRequest> requests = IntStream.range(0, selectionCount)
                .mapToObj(index -> new StoreServiceIntervalRequest(
                        command.storeId(), command.startAt(), command.serviceEndAt()))
                .toList();
        var results = intervalService.validateServiceIntervals(requests);
        if (results == null || results.size() != requests.size()
                || !IntStream.range(0, requests.size()).allMatch(index -> {
                    var request = requests.get(index);
                    var result = results.get(index);
                    return result != null
                            && result.storeId() == request.storeId()
                            && Objects.equals(result.startAt(), request.startAt())
                            && Objects.equals(result.serviceEndAt(), request.serviceEndAt())
                            && result.status() == StoreServiceIntervalStatus.ACCEPTING;
                })) {
            throw new ServiceException(MenuHoldErrorCode.INELIGIBLE_MENU);
        }
    }

    private static boolean matchesResolvedInterval(
            TemporaryMenuHoldContracts.Create command,
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

    private static TemporaryMenuHoldContracts.Result toResult(MenuHold hold) {
        if (hold.getReservationHoldId() == null || hold.getExpiresAt() == null) {
            throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
        TemporaryMenuHoldContracts.State state = switch (hold.getStatus()) {
            case ACTIVE -> TemporaryMenuHoldContracts.State.ACTIVE;
            case RECONCILIATION_REQUIRED ->
                    TemporaryMenuHoldContracts.State.RECONCILIATION_REQUIRED;
            case CONFIRMED -> TemporaryMenuHoldContracts.State.CONFIRMED;
            case RELEASED -> TemporaryMenuHoldContracts.State.RELEASED;
            case EXPIRED -> TemporaryMenuHoldContracts.State.EXPIRED;
            case FULFILLED -> TemporaryMenuHoldContracts.State.FULFILLED;
        };
        return new TemporaryMenuHoldContracts.Result(
                TemporaryMenuHoldContracts.Presence.HOLD_PRESENT,
                state,
                hold.getReservationId());
    }

    private static TemporaryMenuHoldContracts.Result noHold() {
        return new TemporaryMenuHoldContracts.Result(
                TemporaryMenuHoldContracts.Presence.NO_HOLD, null, null);
    }

    private static String deriveRestoreOperationId(String operationId) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(operationId.getBytes(StandardCharsets.UTF_8));
            return RESTORE_PREFIX + HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required", exception);
        }
    }

    private static ServiceException idempotencyReuse() {
        return new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
    }

    private static ServiceException idempotencyReuse(Throwable cause) {
        ServiceException exception = idempotencyReuse();
        exception.initCause(cause);
        return exception;
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
