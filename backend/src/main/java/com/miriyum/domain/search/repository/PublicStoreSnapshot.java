package com.miriyum.domain.search.repository;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
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
        boolean reservationEnabled,
        boolean menuHoldEnabled,
        boolean pickupEnabled
) {
}
