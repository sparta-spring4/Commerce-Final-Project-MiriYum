package com.miriyum.domain.menuhold.inventory.entity;

import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
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
@Table(name = "menu_inventory_policy_audits")
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class MenuInventoryPolicyAudit extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    @Column(name = "menu_inventory_policy_audit_id")
    private Long id;

    @Column(name = "operator_account_id", nullable = false)
    private long operatorAccountId;

    @Column(name = "command_type", nullable = false, length = 60)
    private String commandType;

    @Column(name = "idempotency_key", nullable = false, length = 36)
    private String idempotencyKey;

    @Column(name = "menu_inventory_bucket_id", nullable = false)
    private long bucketId;

    @Column(name = "previous_menu_inventory_bucket_id")
    private Long previousBucketId;

    @Column(name = "inventory_policy_version", nullable = false)
    private long policyVersion;

    @Column(name = "total_supply_before", nullable = false)
    private int totalSupplyBefore;

    @Column(name = "total_supply_delta", nullable = false)
    private int totalSupplyDelta;

    @Column(name = "total_supply_after", nullable = false)
    private int totalSupplyAfter;

    @Column(name = "online_capacity_before", nullable = false)
    private int onlineCapacityBefore;

    @Column(name = "online_capacity_delta", nullable = false)
    private int onlineCapacityDelta;

    @Column(name = "online_capacity_after", nullable = false)
    private int onlineCapacityAfter;

    @Column(name = "onsite_capacity_before", nullable = false)
    private int onsiteCapacityBefore;

    @Column(name = "onsite_capacity_delta", nullable = false)
    private int onsiteCapacityDelta;

    @Column(name = "onsite_capacity_after", nullable = false)
    private int onsiteCapacityAfter;

    @Column(name = "shared_capacity_before", nullable = false)
    private int sharedCapacityBefore;

    @Column(name = "shared_capacity_delta", nullable = false)
    private int sharedCapacityDelta;

    @Column(name = "shared_capacity_after", nullable = false)
    private int sharedCapacityAfter;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_before", length = 20)
    private InventoryAvailabilityStatus availabilityBefore;

    @Enumerated(EnumType.STRING)
    @Column(name = "availability_after", nullable = false, length = 20)
    private InventoryAvailabilityStatus availabilityAfter;

    public static MenuInventoryPolicyAudit created(
            long operatorAccountId,
            String commandType,
            String idempotencyKey,
            MenuInventoryBucket created
    ) {
        if (operatorAccountId <= 0 || commandType == null || commandType.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank()
                || created == null || created.getId() == null) {
            throw new IllegalArgumentException("invalid inventory policy audit");
        }
        MenuInventoryPolicyAudit audit = new MenuInventoryPolicyAudit();
        audit.operatorAccountId = operatorAccountId;
        audit.commandType = commandType;
        audit.idempotencyKey = idempotencyKey;
        audit.bucketId = created.getId();
        audit.policyVersion = created.getInventoryPolicyVersion();
        audit.totalSupplyAfter = created.getTotalSupply();
        audit.totalSupplyDelta = audit.totalSupplyAfter;
        audit.onlineCapacityAfter = created.getOnlineHoldCapacity();
        audit.onlineCapacityDelta = audit.onlineCapacityAfter;
        audit.onsiteCapacityAfter = created.getOnsiteCapacity();
        audit.onsiteCapacityDelta = audit.onsiteCapacityAfter;
        audit.sharedCapacityAfter = created.getSharedCapacity();
        audit.sharedCapacityDelta = audit.sharedCapacityAfter;
        audit.availabilityAfter = created.getAvailabilityStatus();
        return audit;
    }

    public static MenuInventoryPolicyAudit updated(
            long operatorAccountId,
            String commandType,
            String idempotencyKey,
            MenuInventoryBucket before,
            MenuInventoryBucket after
    ) {
        if (operatorAccountId <= 0 || commandType == null || commandType.isBlank()
                || idempotencyKey == null || idempotencyKey.isBlank()
                || before == null || before.getId() == null
                || after == null || after.getId() == null) {
            throw new IllegalArgumentException("invalid inventory policy audit");
        }
        MenuInventoryPolicyAudit audit = new MenuInventoryPolicyAudit();
        audit.operatorAccountId = operatorAccountId;
        audit.commandType = commandType;
        audit.idempotencyKey = idempotencyKey;
        audit.bucketId = after.getId();
        audit.previousBucketId = before.getId();
        audit.policyVersion = after.getInventoryPolicyVersion();
        audit.totalSupplyBefore = before.getTotalSupply();
        audit.totalSupplyAfter = after.getTotalSupply();
        audit.totalSupplyDelta = audit.totalSupplyAfter - audit.totalSupplyBefore;
        audit.onlineCapacityBefore = before.getOnlineHoldCapacity();
        audit.onlineCapacityAfter = after.getOnlineHoldCapacity();
        audit.onlineCapacityDelta = audit.onlineCapacityAfter - audit.onlineCapacityBefore;
        audit.onsiteCapacityBefore = before.getOnsiteCapacity();
        audit.onsiteCapacityAfter = after.getOnsiteCapacity();
        audit.onsiteCapacityDelta = audit.onsiteCapacityAfter - audit.onsiteCapacityBefore;
        audit.sharedCapacityBefore = before.getSharedCapacity();
        audit.sharedCapacityAfter = after.getSharedCapacity();
        audit.sharedCapacityDelta = audit.sharedCapacityAfter - audit.sharedCapacityBefore;
        audit.availabilityBefore = before.getAvailabilityStatus();
        audit.availabilityAfter = after.getAvailabilityStatus();
        return audit;
    }
}
