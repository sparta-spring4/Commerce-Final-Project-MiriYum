package com.miriyum.domain.menuhold.controller.dto;

import com.miriyum.domain.menuhold.inventory.dto.InventoryBucketView;
import com.miriyum.domain.menuhold.inventory.model.InventoryAvailabilityStatus;
import java.time.LocalDate;
import java.time.LocalTime;

public record MenuInventoryBucketResponse(
        long inventoryBucketId,
        long menuId,
        LocalDate serviceDate,
        LocalTime startTime,
        LocalDate endDate,
        LocalTime endTime,
        long policyVersion,
        int totalSupply,
        InventoryPoolsRequest pools,
        boolean sharedOnlineAllowed,
        int availableOnlineQuantity,
        InventoryAvailabilityStatus availabilityStatus
) {
    public static MenuInventoryBucketResponse from(InventoryBucketView view) {
        return new MenuInventoryBucketResponse(
                view.inventoryBucketId(), view.menuId(), view.serviceDate(),
                view.startTime(), view.endDate(), view.endTime(),
                view.policyVersion(), view.totalSupply(),
                new InventoryPoolsRequest(view.onlineHoldCapacity(),
                        view.onsiteCapacity(), view.sharedCapacity()),
                view.sharedOnlineAllowed(),
                view.availableOnlineQuantity(), view.availabilityStatus());
    }
}
