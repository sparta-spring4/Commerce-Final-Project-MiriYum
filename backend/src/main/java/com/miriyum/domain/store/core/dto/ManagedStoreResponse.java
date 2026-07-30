package com.miriyum.domain.store.core.dto;

import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.enums.VerificationStatus;

public record ManagedStoreResponse(
        Long storeId,
        String name,
        Region region,
        String address,
        String storeCategoryCode,
        VerificationStatus verificationStatus,
        OperationStatus operationStatus,
        PickupEligibility pickupEligibility,
        StoreModesRequest modes
) {

    public static ManagedStoreResponse from(Store store) {
        return new ManagedStoreResponse(
                store.getId(),
                store.getName(),
                store.getRegion(),
                store.getAddress(),
                store.getStoreCategoryCode(),
                store.getVerificationStatus(),
                store.getOperationStatus(),
                store.getPickupEligibility(),
                new StoreModesRequest(
                        store.isReservationEnabled(),
                        store.isMenuHoldEnabled(),
                        store.isPickupEnabled()));
    }
}
