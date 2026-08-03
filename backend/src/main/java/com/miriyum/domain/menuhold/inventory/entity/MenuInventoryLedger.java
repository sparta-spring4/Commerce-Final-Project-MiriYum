package com.miriyum.domain.menuhold.inventory.entity;

import com.miriyum.domain.menuhold.inventory.model.InventoryLedgerOperation;
import com.miriyum.domain.menuhold.inventory.model.InventoryPoolType;
import com.miriyum.global.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

@Entity
@Table(name = "menu_inventory_ledger")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuInventoryLedger extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_inventory_ledger_id")
    private Long id;

    @Column(name = "command_id", nullable = false, length = 100)
    private String commandId;

    @Column(name = "source_command_id", length = 100)
    private String sourceCommandId;

    @Column(name = "menu_inventory_bucket_id", nullable = false)
    private long bucketId;

    @Enumerated(EnumType.STRING)
    @Column(name = "operation_type", nullable = false, length = 20)
    private InventoryLedgerOperation operationType;

    @Enumerated(EnumType.STRING)
    @Column(name = "pool_type", nullable = false, length = 20)
    private InventoryPoolType poolType;

    @Column(name = "quantity_delta", nullable = false)
    private int quantityDelta;

    @Column(name = "quantity_before", nullable = false)
    private int quantityBefore;

    @Column(name = "quantity_after", nullable = false)
    private int quantityAfter;

    public static MenuInventoryLedger acquired(
            String commandId,
            long bucketId,
            InventoryPoolType poolType,
            int quantity,
            int remainingAfter
    ) {
        return create(commandId, null, bucketId, InventoryLedgerOperation.ACQUIRE, poolType,
                -quantity, remainingAfter + quantity, remainingAfter);
    }

    public static MenuInventoryLedger restored(
            String commandId,
            String sourceCommandId,
            long bucketId,
            InventoryPoolType poolType,
            int quantity,
            int remainingAfter
    ) {
        return create(commandId, sourceCommandId, bucketId, InventoryLedgerOperation.RESTORE, poolType,
                quantity, remainingAfter - quantity, remainingAfter);
    }

    private static MenuInventoryLedger create(
            String commandId,
            String sourceCommandId,
            long bucketId,
            InventoryLedgerOperation operationType,
            InventoryPoolType poolType,
            int delta,
            int before,
            int after
    ) {
        MenuInventoryLedger ledger = new MenuInventoryLedger();
        ledger.commandId = commandId;
        ledger.sourceCommandId = sourceCommandId;
        ledger.bucketId = bucketId;
        ledger.operationType = operationType;
        ledger.poolType = poolType;
        ledger.quantityDelta = delta;
        ledger.quantityBefore = before;
        ledger.quantityAfter = after;
        return ledger;
    }
}
