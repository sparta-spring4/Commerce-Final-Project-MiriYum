package com.miriyum.domain.store.core.dto;

import com.miriyum.domain.store.core.entity.Store;
import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import com.miriyum.domain.store.core.enums.VerificationStatus;

public record ManagedStoreResponse(
        String storeId,
        String name,
        Region region,
        String address,
        String timeZoneId,
        String storeCategoryCode,
        VerificationStatus verificationStatus,
        OperationStatus operationStatus,
        PickupEligibility pickupEligibility,
        StoreModesRequest modes,
        StoreGeocodingResponse geocoding
) {

    public ManagedStoreResponse {
        if (geocoding == null) {
            geocoding = StoreGeocodingResponse.legacyUnverified();
        }
    }

    public static ManagedStoreResponse from(Store store) {
        return new ManagedStoreResponse(
                String.valueOf(store.getId()),
                store.getName(),
                store.getRegion(),
                store.getAddress(),
                store.getTimeZoneId(),
                store.getStoreCategoryCode(),
                store.getVerificationStatus(),
                store.getOperationStatus(),
                store.getPickupEligibility(),
                new StoreModesRequest(
                        store.isReservationEnabled(),
                        store.isMenuHoldEnabled(),
                        store.isPickupEnabled()),
                StoreGeocodingResponse.from(store));
    }
}
