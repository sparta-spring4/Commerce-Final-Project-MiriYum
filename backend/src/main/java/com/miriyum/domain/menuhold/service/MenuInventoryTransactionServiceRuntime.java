package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquireResult;
import com.miriyum.domain.menuhold.dto.MenuInventoryAcquiredItem;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailability;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityDateQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryAvailabilityQuery;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreCommand;
import com.miriyum.domain.menuhold.dto.MenuInventoryRestoreResult;
import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquireRequest;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquisitionResult;
import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketKey;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.inventory.dto.OnlineInventoryAvailabilityView;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 공개 픽업 수량 계약을 기존 중앙 재고 버킷·원장 런타임에 연결한다. */
@Service
@RequiredArgsConstructor
public class MenuInventoryTransactionServiceRuntime
        implements MenuInventoryTransactionService {

    private final MenuInventoryBucketRepository bucketRepository;
    private final MenuInventoryService inventoryService;

    @Override
    @Transactional(readOnly = true)
    public List<MenuInventoryAvailability> findOnlineAvailability(
            MenuInventoryAvailabilityQuery query
    ) {
        List<OnlineInventoryAvailabilityView> views =
                bucketRepository.findCurrentOnlineAvailability(
                        query.menuIds(), query.serviceDate(), query.startTime(),
                        query.endDate(), query.endTime());
        Set<Long> resultMenuIds = new HashSet<>();
        views.forEach(view -> resultMenuIds.add(view.getMenuId()));
        if (views.size() != query.menuIds().size()
                || !resultMenuIds.equals(new HashSet<>(query.menuIds()))) {
            throw new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
        }
        return views.stream()
                .sorted(Comparator.comparingLong(OnlineInventoryAvailabilityView::getMenuId))
                .map(MenuInventoryTransactionServiceRuntime::toAvailability)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuInventoryAvailability> findExistingOnlineAvailability(
            MenuInventoryAvailabilityQuery query
    ) {
        return bucketRepository.findCurrentOnlineAvailability(
                        query.menuIds(), query.serviceDate(), query.startTime(),
                        query.endDate(), query.endTime()).stream()
                .sorted(Comparator.comparingLong(OnlineInventoryAvailabilityView::getMenuId))
                .map(MenuInventoryTransactionServiceRuntime::toAvailability)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public List<MenuInventoryAvailability> findOnlineAvailabilityByDate(
            MenuInventoryAvailabilityDateQuery query
    ) {
        return bucketRepository.findCurrentOnlineAvailabilityByDate(
                        query.menuIds(), query.pickupDate()).stream()
                .map(MenuInventoryTransactionServiceRuntime::toAvailability)
                .toList();
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MenuInventoryAcquireResult acquire(MenuInventoryAcquireCommand command) {
        List<InventoryAcquireRequest.Selection> selections = command.selections().stream()
                .map(selection -> new InventoryAcquireRequest.Selection(
                        selection.menuId(), selection.serviceDate(),
                        selection.startTime(), selection.endDate(), selection.endTime(),
                        selection.inventoryPolicyVersion(), selection.quantity()))
                .toList();
        List<InventoryAcquisitionResult> acquired = inventoryService.acquireInventory(
                new InventoryAcquireRequest(command.operationId(), selections));
        Map<InventoryBucketKey, InventoryAcquisitionResult> acquiredByKey =
                indexAcquiredResults(selections, acquired);
        return new MenuInventoryAcquireResult(
                command.operationId(),
                selections.stream()
                        .map(InventoryAcquireRequest.Selection::key)
                        .map(key -> requireAcquiredResult(acquiredByKey, key))
                        .toList());
    }

    private static Map<InventoryBucketKey, InventoryAcquisitionResult> indexAcquiredResults(
            List<InventoryAcquireRequest.Selection> selections,
            List<InventoryAcquisitionResult> acquired
    ) {
        Map<InventoryBucketKey, InventoryAcquireRequest.Selection> selectionsByKey =
                new HashMap<>();
        selections.forEach(selection -> selectionsByKey.put(selection.key(), selection));
        if (acquired == null || acquired.size() != selectionsByKey.size()) {
            throw mismatchedAcquisitionResults();
        }
        Map<InventoryBucketKey, InventoryAcquisitionResult> acquiredByKey = new HashMap<>();
        for (InventoryAcquisitionResult result : acquired) {
            InventoryAcquireRequest.Selection selection = result == null
                    ? null : selectionsByKey.get(result.bucketKey());
            if (result == null
                    || selection == null
                    || result.inventoryBucketId() <= 0
                    || result.menuId() != result.bucketKey().menuId()
                    || result.inventoryPolicyVersion()
                    != result.bucketKey().inventoryPolicyVersion()
                    || result.quantity() != selection.quantity()
                    || acquiredByKey.putIfAbsent(result.bucketKey(), result) != null) {
                throw mismatchedAcquisitionResults();
            }
        }
        return Map.copyOf(acquiredByKey);
    }

    private static IllegalStateException mismatchedAcquisitionResults() {
        return new IllegalStateException(
                "acquired inventory results do not match requested selections");
    }

    private static MenuInventoryAcquiredItem requireAcquiredResult(
            Map<InventoryBucketKey, InventoryAcquisitionResult> acquiredByKey,
            InventoryBucketKey key
    ) {
        InventoryAcquisitionResult result = acquiredByKey.get(key);
        if (result == null) {
            throw mismatchedAcquisitionResults();
        }
        return new MenuInventoryAcquiredItem(
                result.inventoryBucketId(), result.menuId(),
                result.inventoryPolicyVersion(), result.quantity());
    }

    @Override
    @Transactional(propagation = Propagation.MANDATORY)
    public MenuInventoryRestoreResult restore(MenuInventoryRestoreCommand command) {
        inventoryService.restoreInventory(new InventoryRestoreRequest(
                command.operationId(), command.sourceAcquireOperationId()));
        return new MenuInventoryRestoreResult(
                command.operationId(), command.sourceAcquireOperationId());
    }

    private static MenuInventoryAvailability toAvailability(
            OnlineInventoryAvailabilityView view
    ) {
        int availableOnlineQuantity = view.getOnlineHoldRemaining()
                + (view.isSharedOnlineAllowed() ? view.getSharedRemaining() : 0);
        return new MenuInventoryAvailability(
                view.getMenuId(),
                view.getInventoryPolicyVersion(),
                view.getTimeZoneId(),
                view.getServiceDate(),
                view.getStartTime(),
                view.getEndDate(),
                view.getEndTime(),
                availableOnlineQuantity,
                view.getAvailabilityStatus() == InventoryAvailabilityStatus.AVAILABLE
                        ? MenuInventoryAvailability.AvailabilityStatus.AVAILABLE
                        : MenuInventoryAvailability.AvailabilityStatus.SOLD_OUT);
    }
}
