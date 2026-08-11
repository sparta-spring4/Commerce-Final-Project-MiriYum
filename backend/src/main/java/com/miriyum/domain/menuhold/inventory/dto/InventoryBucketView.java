package com.miriyum.domain.menuhold.inventory.dto;

import com.miriyum.domain.menuhold.inventory.entity.MenuInventoryBucket;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import java.time.LocalDate;
import java.time.LocalTime;

public record InventoryBucketView(
        long inventoryBucketId,
        long menuId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        long policyVersion,
        int totalSupply,
        int onlineHoldCapacity,
        int onlineHoldRemaining,
        int onsiteCapacity,
        int onsiteRemaining,
        int sharedCapacity,
        int sharedRemaining,
        boolean sharedOnlineAllowed,
        int availableOnlineQuantity,
        InventoryAvailabilityStatus availabilityStatus
) {
    public static InventoryBucketView from(MenuInventoryBucket bucket) {
        return new InventoryBucketView(
                bucket.getId(), bucket.getMenuId(), bucket.getServiceDate(),
                bucket.getStartTime(), bucket.getEndDate(), bucket.getEndTime(),
                bucket.getInventoryPolicyVersion(), bucket.getTotalSupply(),
                bucket.getOnlineHoldCapacity(), bucket.getOnlineHoldRemaining(),
                bucket.getOnsiteCapacity(), bucket.getOnsiteRemaining(),
                bucket.getSharedCapacity(), bucket.getSharedRemaining(),
                bucket.isSharedOnlineAllowed(), bucket.availableOnlineQuantity(),
                bucket.getAvailabilityStatus());
    }
}
