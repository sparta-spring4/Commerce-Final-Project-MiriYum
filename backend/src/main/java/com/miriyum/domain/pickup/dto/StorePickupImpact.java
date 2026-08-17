package com.miriyum.domain.pickup.dto;

import java.util.Set;

/** 한 Store의 확정 Pickup 영향 projection이다. */
public record StorePickupImpact(long storeId, long confirmedCount, Set<Long> pickupIds) {
    public StorePickupImpact {
        if (storeId <= 0 || confirmedCount < 0 || pickupIds == null
                || confirmedCount != pickupIds.size()) {
            throw new IllegalArgumentException("pickup impact is invalid");
        }
        pickupIds = Set.copyOf(pickupIds);
    }
}
