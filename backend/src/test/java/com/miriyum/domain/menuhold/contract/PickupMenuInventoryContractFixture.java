package com.miriyum.domain.menuhold.contract;

import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireResult;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireSelection;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquiredItem;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityDateQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import com.miriyum.domain.menuhold.service.MenuInventoryTransactionService;
import com.miriyum.global.exception.ServiceException;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/** 픽업 도메인이 production runtime 없이 공개 수량 계약을 검증하는 테스트 fixture다. */
public final class PickupMenuInventoryContractFixture
        implements MenuInventoryTransactionService {

    private final List<MenuInventoryAvailability> availability;
    private final Map<SelectionKey, Long> inventoryBucketIdsBySelection;
    private final ServiceException failure;
    private final List<MenuInventoryAvailabilityQuery> availabilityQueries = new ArrayList<>();
    private final List<MenuInventoryAvailabilityDateQuery> dateAvailabilityQueries =
            new ArrayList<>();
    private final List<MenuInventoryAcquireCommand> acquireCommands = new ArrayList<>();
    private final List<MenuInventoryRestoreCommand> restoreCommands = new ArrayList<>();

    private PickupMenuInventoryContractFixture(
            List<MenuInventoryAvailability> availability,
            Map<MenuInventoryAcquireSelection, Long> inventoryBucketIdsBySelection,
            ServiceException failure
    ) {
        this.availability = List.copyOf(availability);
        this.inventoryBucketIdsBySelection = inventoryBucketIdsBySelection.entrySet().stream()
                .collect(Collectors.toUnmodifiableMap(
                        entry -> SelectionKey.from(entry.getKey()), Map.Entry::getValue));
        this.failure = failure;
    }

    public static PickupMenuInventoryContractFixture succeeding(
            List<MenuInventoryAvailability> availability,
            Map<MenuInventoryAcquireSelection, Long> inventoryBucketIdsBySelection
    ) {
        if (availability == null || inventoryBucketIdsBySelection == null) {
            throw new IllegalArgumentException(
                    "availability and inventoryBucketIdsBySelection must not be null");
        }
        return new PickupMenuInventoryContractFixture(
                availability, inventoryBucketIdsBySelection, null);
    }

    public static PickupMenuInventoryContractFixture failing(ServiceException failure) {
        if (failure == null) {
            throw new IllegalArgumentException("failure must not be null");
        }
        return new PickupMenuInventoryContractFixture(List.of(), Map.of(), failure);
    }

    @Override
    public List<MenuInventoryAvailability> findOnlineAvailability(
            MenuInventoryAvailabilityQuery query
    ) {
        availabilityQueries.add(query);
        throwIfConfigured();
        return availability;
    }

    @Override
    public List<MenuInventoryAvailability> findOnlineAvailabilityByDate(
            MenuInventoryAvailabilityDateQuery query
    ) {
        dateAvailabilityQueries.add(query);
        throwIfConfigured();
        return availability;
    }

    @Override
    public MenuInventoryAcquireResult acquire(MenuInventoryAcquireCommand command) {
        acquireCommands.add(command);
        throwIfConfigured();
        return new MenuInventoryAcquireResult(
                command.operationId(),
                command.selections().stream()
                        .map(selection -> new MenuInventoryAcquiredItem(
                                requireInventoryBucketId(selection),
                                selection.menuId(),
                                selection.inventoryPolicyVersion(),
                                selection.quantity()))
                        .toList());
    }

    @Override
    public MenuInventoryRestoreResult restore(MenuInventoryRestoreCommand command) {
        restoreCommands.add(command);
        throwIfConfigured();
        return new MenuInventoryRestoreResult(
                command.operationId(), command.sourceAcquireOperationId());
    }

    public List<MenuInventoryAvailabilityQuery> availabilityQueries() {
        return List.copyOf(availabilityQueries);
    }

    public List<MenuInventoryAvailabilityDateQuery> dateAvailabilityQueries() {
        return List.copyOf(dateAvailabilityQueries);
    }

    public List<MenuInventoryAcquireCommand> acquireCommands() {
        return List.copyOf(acquireCommands);
    }

    public List<MenuInventoryRestoreCommand> restoreCommands() {
        return List.copyOf(restoreCommands);
    }

    private void throwIfConfigured() {
        if (failure != null) {
            throw failure;
        }
    }

    private long requireInventoryBucketId(MenuInventoryAcquireSelection selection) {
        Long inventoryBucketId = inventoryBucketIdsBySelection.get(SelectionKey.from(selection));
        if (inventoryBucketId == null || inventoryBucketId <= 0) {
            throw new IllegalArgumentException(
                    "positive inventory bucket ID must be configured for selection");
        }
        return inventoryBucketId;
    }

    private record SelectionKey(
            long menuId,
            LocalDate serviceDate,
            LocalTime startTime,
            LocalDate endDate,
            LocalTime endTime,
            long inventoryPolicyVersion
    ) {
        private static SelectionKey from(MenuInventoryAcquireSelection selection) {
            if (selection == null) {
                throw new IllegalArgumentException("inventory selection must not be null");
            }
            return new SelectionKey(
                    selection.menuId(), selection.serviceDate(), selection.startTime(),
                    selection.endDate(), selection.endTime(),
                    selection.inventoryPolicyVersion());
        }
    }
}
