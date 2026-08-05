package com.miriyum.domain.store.search.repository;

import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.PickupEligibility;
import com.miriyum.domain.store.core.enums.Region;
import java.util.List;

public record PublicStoreSnapshot(
        long storeId,
        String name,
        String description,
        Region region,
        String address,
        String timeZoneId,
        String storeCategoryCode,
        List<String> tags,
        OperationStatus operationStatus,
        PickupEligibility pickupEligibility,
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled
) {
}
