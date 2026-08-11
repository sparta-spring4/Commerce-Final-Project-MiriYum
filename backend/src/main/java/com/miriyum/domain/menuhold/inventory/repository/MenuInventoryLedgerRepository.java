package com.miriyum.domain.menuhold.inventory.repository;

import com.miriyum.domain.menuhold.inventory.dto.InventoryAllocationResult;
import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryLedger;
import com.miriyum.domain.menuhold.inventory.model.InventoryLedgerOperation;
import com.miriyum.domain.menuhold.inventory.model.InventoryPoolType;
import jakarta.persistence.LockModeType;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface MenuInventoryLedgerRepository
        extends JpaRepository<MenuInventoryLedger, Long> {

    List<MenuInventoryLedger> findAllByOperationIdAndOperationTypeOrderByBucketIdAscPoolTypeAsc(
            String operationId,
            InventoryLedgerOperation operationType);

    default List<InventoryAllocationResult> findAcquireResults(String operationId) {
        return findResults(operationId, InventoryLedgerOperation.ACQUIRE);
    }

    default boolean existsRestoreForSourceOperation(String sourceOperationId) {
        return existsBySourceOperationIdAndOperationType(
                sourceOperationId, InventoryLedgerOperation.RESTORE);
    }

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("""
            select ledger
            from MenuInventoryLedger ledger
            where ledger.sourceOperationId = :sourceOperationId
              and ledger.operationType = :operationType
            order by ledger.bucketId asc, ledger.poolType asc
            """)
    List<MenuInventoryLedger> findAllBySourceOperationIdForUpdate(
            @Param("sourceOperationId") String sourceOperationId,
            @Param("operationType") InventoryLedgerOperation operationType);

    default boolean existsRestoreForSourceOperationForUpdate(String sourceOperationId) {
        return !findAllBySourceOperationIdForUpdate(
                sourceOperationId, InventoryLedgerOperation.RESTORE).isEmpty();
    }

    private List<InventoryAllocationResult> findResults(
            String operationId,
            InventoryLedgerOperation operationType
    ) {
        Map<Long, int[]> quantities = new LinkedHashMap<>();
        findAllByOperationIdAndOperationTypeOrderByBucketIdAscPoolTypeAsc(
                operationId, operationType).forEach(ledger -> {
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

    boolean existsBySourceOperationIdAndOperationType(
            String sourceOperationId,
            InventoryLedgerOperation operationType);
}
