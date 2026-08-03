package com.miriyum.domain.menuhold.service;

import com.miriyum.domain.menuhold.error.MenuHoldErrorCode;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAcquireRequest;
import com.miriyum.domain.menuhold.inventory.dto.InventoryAllocationResult;
import com.miriyum.domain.menuhold.inventory.dto.InventoryRestoreRequest;
import com.miriyum.domain.menuhold.inventory.entity.InventoryAllocation;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryLedger;
import com.miriyum.domain.menuhold.inventory.model.InventoryLedgerOperation;
import com.miriyum.domain.menuhold.inventory.model.InventoryPoolType;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryBucketRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryCommandRepository;
import com.miriyum.domain.menuhold.inventory.repository.MenuInventoryLedgerRepository;
import com.miriyum.global.exception.CommonErrorCode;
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

@Service
@RequiredArgsConstructor
public class MenuHoldService {

    private final MenuInventoryBucketRepository bucketRepository;
    private final MenuInventoryLedgerRepository ledgerRepository;
    private final MenuInventoryCommandRepository commandRepository;

    @Transactional(propagation = Propagation.MANDATORY)
    public List<InventoryAllocationResult> acquireInventory(InventoryAcquireRequest request) {
        Map<Long, Integer> quantitiesByBucketId = new HashMap<>();
        for (InventoryAcquireRequest.Selection selection : request.selections()) {
            Long bucketId = bucketRepository.findBucketId(selection.key());
            if (bucketId == null) {
                throw new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
            }
            quantitiesByBucketId.merge(bucketId, selection.quantity(), Math::addExact);
        }
        boolean newCommand = commandRepository.claimOrValidate(
                request.commandId(),
                InventoryLedgerOperation.ACQUIRE,
                acquireCanonicalInput(quantitiesByBucketId),
                null);
        if (!newCommand) {
            List<InventoryAllocationResult> replay = ledgerRepository
                    .findAcquireResults(request.commandId());
            if (replay.isEmpty()) {
                throw new IllegalStateException("completed inventory command has no acquire ledger");
            }
            if (!matchesRequestedQuantities(replay, quantitiesByBucketId)) {
                throw new ServiceException(CommonErrorCode.IDEMPOTENCY_KEY_REUSED);
            }
            return replay;
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
            appendAcquireEvents(request.commandId(), bucket, allocation, events);
        }
        ledgerRepository.saveAll(events);
        return List.copyOf(results);
    }

    private static boolean matchesRequestedQuantities(
            List<InventoryAllocationResult> replay,
            Map<Long, Integer> quantitiesByBucketId
    ) {
        if (replay.size() != quantitiesByBucketId.size()) {
            return false;
        }
        return replay.stream().allMatch(result ->
                quantitiesByBucketId.getOrDefault(result.bucketId(), -1)
                        == result.onlineHoldQuantity() + result.sharedQuantity());
    }

    private static String acquireCanonicalInput(Map<Long, Integer> quantitiesByBucketId) {
        return quantitiesByBucketId.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .map(entry -> entry.getKey() + ":" + entry.getValue())
                .collect(Collectors.joining(";", "ACQUIRE|", ""));
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public void restoreInventory(InventoryRestoreRequest request) {
        List<InventoryAllocationResult> acquired = ledgerRepository
                .findAcquireResults(request.acquireCommandId());
        if (acquired.isEmpty()) {
            throw new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
        }
        boolean newCommand = commandRepository.claimOrValidate(
                request.commandId(),
                InventoryLedgerOperation.RESTORE,
                request.acquireCommandId(),
                request.acquireCommandId());
        if (!newCommand) {
            return;
        }
        Map<Long, InventoryAllocationResult> allocationsByBucketId = acquired.stream()
                .collect(Collectors.toMap(
                        InventoryAllocationResult::bucketId, allocation -> allocation));
        if (ledgerRepository.existsRestoreForSourceCommand(request.acquireCommandId())) {
            return;
        }
        List<Long> orderedIds = allocationsByBucketId.keySet().stream().sorted().toList();
        List<MenuInventoryBucket> buckets = bucketRepository.findAllForUpdate(orderedIds);
        if (buckets.size() != orderedIds.size()) {
            throw new ServiceException(MenuHoldErrorCode.BUCKET_NOT_FOUND);
        }
        if (ledgerRepository.existsRestoreForSourceCommand(request.acquireCommandId())) {
            return;
        }
        List<MenuInventoryLedger> events = new ArrayList<>();
        for (MenuInventoryBucket bucket : buckets) {
            InventoryAllocationResult requested = allocationsByBucketId.get(bucket.getId());
            InventoryAllocation allocation = new InventoryAllocation(
                    requested.onlineHoldQuantity(), requested.sharedQuantity());
            bucket.validateRestore(allocation);
            int updated = bucketRepository.incrementIfCurrent(
                    bucket.getId(), bucket.getLockVersion(),
                    allocation.onlineHoldQuantity(), allocation.sharedQuantity());
            if (updated != 1) {
                throw new ServiceException(MenuHoldErrorCode.INVENTORY_STATE_CONFLICT);
            }
            appendRestoreEvents(
                    request.commandId(), request.acquireCommandId(), bucket, allocation, events);
        }
        ledgerRepository.saveAll(events);
    }

    private static void appendAcquireEvents(
            String commandId,
            MenuInventoryBucket bucket,
            InventoryAllocation allocation,
            List<MenuInventoryLedger> events
    ) {
        if (allocation.onlineHoldQuantity() > 0) {
            events.add(MenuInventoryLedger.acquired(
                    commandId,
                    bucket.getId(),
                    InventoryPoolType.ONLINE_HOLD,
                    allocation.onlineHoldQuantity(),
                    bucket.getOnlineHoldRemaining() - allocation.onlineHoldQuantity()));
        }
        if (allocation.sharedQuantity() > 0) {
            events.add(MenuInventoryLedger.acquired(
                    commandId,
                    bucket.getId(),
                    InventoryPoolType.SHARED,
                    allocation.sharedQuantity(),
                    bucket.getSharedRemaining() - allocation.sharedQuantity()));
        }
    }

    private static void appendRestoreEvents(
            String commandId,
            String sourceCommandId,
            MenuInventoryBucket bucket,
            InventoryAllocation allocation,
            List<MenuInventoryLedger> events
    ) {
        if (allocation.onlineHoldQuantity() > 0) {
            events.add(MenuInventoryLedger.restored(
                    commandId,
                    sourceCommandId,
                    bucket.getId(),
                    InventoryPoolType.ONLINE_HOLD,
                    allocation.onlineHoldQuantity(),
                    bucket.getOnlineHoldRemaining() + allocation.onlineHoldQuantity()));
        }
        if (allocation.sharedQuantity() > 0) {
            events.add(MenuInventoryLedger.restored(
                    commandId,
                    sourceCommandId,
                    bucket.getId(),
                    InventoryPoolType.SHARED,
                    allocation.sharedQuantity(),
                    bucket.getSharedRemaining() + allocation.sharedQuantity()));
        }
    }
}
