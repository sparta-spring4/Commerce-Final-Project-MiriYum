package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquireRequest;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAllocationResult;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.inventory.entity.InventoryAllocation;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryLedger;
import com.miriyum.domain.menuhold.inventory.model.InventoryPoolType;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryLedgerRepository;
import com.miriyum.global.exception.ServiceException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 메뉴 수량 버킷의 확보와 복구를 소유하는 내부 런타임 서비스다. */
@Service
@RequiredArgsConstructor
class MenuInventoryService {

    private final MenuInventoryBucketRepository bucketRepository;
    private final MenuInventoryLedgerRepository ledgerRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    List<InventoryAllocationResult> acquireInventory(InventoryAcquireRequest request) {
        Map<Long, Integer> quantitiesByBucketId = new HashMap<>();
        for (InventoryAcquireRequest.Selection selection : request.selections()) {
            Long bucketId = bucketRepository.findBucketId(selection.key());
            if (bucketId == null) {
                throw new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
            }
            quantitiesByBucketId.merge(bucketId, selection.quantity(), Math::addExact);
        }
        List<Long> orderedIds = quantitiesByBucketId.keySet().stream().sorted().toList();
        List<MenuInventoryBucket> buckets = bucketRepository.findAllForUpdate(orderedIds);
        if (buckets.size() != orderedIds.size()) {
            throw new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
        }

        List<InventoryAllocationResult> results = new ArrayList<>();
        List<MenuInventoryLedger> events = new ArrayList<>();
        for (MenuInventoryBucket bucket : buckets) {
            InventoryAllocation allocation = bucket.planAcquire(quantitiesByBucketId.get(bucket.getId()));
            int updated = bucketRepository.decrementIfCurrent(
                    bucket.getId(), bucket.getLockVersion(),
                    allocation.onlineHoldQuantity(), allocation.sharedQuantity());
            if (updated != 1) {
                throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
            }
            results.add(new InventoryAllocationResult(
                    bucket.getId(),
                    allocation.onlineHoldQuantity(),
                    allocation.sharedQuantity()));
            appendAcquireEvents(request.operationId(), bucket, allocation, events);
        }
        ledgerRepository.saveAll(events);
        return List.copyOf(results);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    void restoreInventory(InventoryRestoreRequest request) {
        List<InventoryAllocationResult> acquired = ledgerRepository
                .findAcquireResults(request.sourceAcquireOperationId());
        if (acquired.isEmpty()) {
            throw new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
        }
        Map<Long, InventoryAllocationResult> allocationsByBucketId = acquired.stream()
                .collect(Collectors.toMap(
                        InventoryAllocationResult::bucketId, allocation -> allocation));
        if (ledgerRepository.existsRestoreForSourceOperation(
                request.sourceAcquireOperationId())) {
            return;
        }
        List<Long> orderedIds = allocationsByBucketId.keySet().stream().sorted().toList();
        List<MenuInventoryBucket> buckets = bucketRepository.findAllForUpdate(orderedIds);
        if (buckets.size() != orderedIds.size()) {
            throw new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
        }
        if (ledgerRepository.existsRestoreForSourceOperationForUpdate(
                request.sourceAcquireOperationId())) {
            return;
        }
        List<MenuInventoryLedger> events = new ArrayList<>();
        for (MenuInventoryBucket bucket : buckets) {
            InventoryAllocationResult requested = allocationsByBucketId.get(bucket.getId());
            InventoryAllocation allocation = new InventoryAllocation(
                    requested.onlineHoldQuantity(), requested.sharedQuantity());
            restoreBucket(request, bucket, allocation, events);

            MenuInventoryBucket current = bucketRepository.findCurrentForUpdate(
                            bucket.getMenuId(), bucket.getServiceDate(),
                            bucket.getStartTime(), bucket.getEndDate(), bucket.getEndTime())
                    .orElseThrow(() -> new ServiceException(
                            MenuHoldErrorCode.BUCKET_NOT_FOUND));
            if (!current.getId().equals(bucket.getId())) {
                restoreBucket(request, current, allocation, events);
            }
        }
        ledgerRepository.saveAll(events);
    }

    private void restoreBucket(
            InventoryRestoreRequest request,
            MenuInventoryBucket bucket,
            InventoryAllocation allocation,
            List<MenuInventoryLedger> events
    ) {
        bucket.validateRestore(allocation);
        int updated = bucketRepository.incrementIfCurrent(
                bucket.getId(), bucket.getLockVersion(),
                allocation.onlineHoldQuantity(), allocation.sharedQuantity());
        if (updated != 1) {
            throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
        }
        appendRestoreEvents(
                request.operationId(), request.sourceAcquireOperationId(),
                bucket, allocation, events);
    }

    private static void appendAcquireEvents(
            String operationId,
            MenuInventoryBucket bucket,
            InventoryAllocation allocation,
            List<MenuInventoryLedger> events
    ) {
        if (allocation.onlineHoldQuantity() > 0) {
            events.add(MenuInventoryLedger.acquired(
                    operationId,
                    bucket.getId(),
                    InventoryPoolType.ONLINE_HOLD,
                    allocation.onlineHoldQuantity(),
                    bucket.getOnlineHoldRemaining() - allocation.onlineHoldQuantity()));
        }
        if (allocation.sharedQuantity() > 0) {
            events.add(MenuInventoryLedger.acquired(
                    operationId,
                    bucket.getId(),
                    InventoryPoolType.SHARED,
                    allocation.sharedQuantity(),
                    bucket.getSharedRemaining() - allocation.sharedQuantity()));
        }
    }

    private static void appendRestoreEvents(
            String operationId,
            String sourceOperationId,
            MenuInventoryBucket bucket,
            InventoryAllocation allocation,
            List<MenuInventoryLedger> events
    ) {
        if (allocation.onlineHoldQuantity() > 0) {
            events.add(MenuInventoryLedger.restored(
                    operationId,
                    sourceOperationId,
                    bucket.getId(),
                    InventoryPoolType.ONLINE_HOLD,
                    allocation.onlineHoldQuantity(),
                    bucket.getOnlineHoldRemaining() + allocation.onlineHoldQuantity()));
        }
        if (allocation.sharedQuantity() > 0) {
            events.add(MenuInventoryLedger.restored(
                    operationId,
                    sourceOperationId,
                    bucket.getId(),
                    InventoryPoolType.SHARED,
                    allocation.sharedQuantity(),
                    bucket.getSharedRemaining() + allocation.sharedQuantity()));
        }
    }
}
