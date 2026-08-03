package com.miriyum.domain.menuhold.inventory.repository;

import com.miriyum.domain.menuhold.inventory.dto.InventoryAllocationResult;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryLedger;
import com.miriyum.domain.menuhold.inventory.model.InventoryLedgerOperation;
import com.miriyum.domain.menuhold.inventory.model.InventoryPoolType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.JpaRepository;

public interface MenuInventoryLedgerRepository
        extends JpaRepository<MenuInventoryLedger, Long> {

    List<MenuInventoryLedger> findAllByCommandIdAndOperationTypeOrderByBucketIdAscPoolTypeAsc(
            String commandId,
            InventoryLedgerOperation operationType);

    default List<InventoryAllocationResult> findAcquireResults(String commandId) {
        return findResults(commandId, InventoryLedgerOperation.ACQUIRE);
    }

    default boolean existsRestoreForSourceCommand(String sourceCommandId) {
        return existsBySourceCommandIdAndOperationType(
                sourceCommandId, InventoryLedgerOperation.RESTORE);
    }

    private List<InventoryAllocationResult> findResults(
            String commandId,
            InventoryLedgerOperation operationType
    ) {
        Map<Long, int[]> quantities = new LinkedHashMap<>();
        findAllByCommandIdAndOperationTypeOrderByBucketIdAscPoolTypeAsc(
                commandId, operationType).forEach(ledger -> {
                    int[] value = quantities.computeIfAbsent(ledger.getBucketId(), ignored -> new int[2]);
                    int quantity = Math.abs(ledger.getQuantityDelta());
                    if (ledger.getPoolType() == InventoryPoolType.ONLINE_HOLD) {
                        value[0] += quantity;
                    } else if (ledger.getPoolType() == InventoryPoolType.SHARED) {
                        value[1] += quantity;
                    }
                });
        return quantities.entrySet().stream()
                .map(entry -> new InventoryAllocationResult(
                        entry.getKey(), entry.getValue()[0], entry.getValue()[1]))
                .toList();
    }

    boolean existsBySourceCommandIdAndOperationType(
            String sourceCommandId,
            InventoryLedgerOperation operationType);
}
