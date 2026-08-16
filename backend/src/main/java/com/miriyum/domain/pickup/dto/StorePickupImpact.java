package com.miriyum.domain.pickup.dto;

/** 한 Store의 확정 Pickup 영향 projection이다. */
public record StorePickupImpact(long storeId, long confirmedCount) {
    public StorePickupImpact {
        if (storeId <= 0 || confirmedCount < 0) {
            throw new IllegalArgumentException("pickup impact is invalid");
        }
    }
}
