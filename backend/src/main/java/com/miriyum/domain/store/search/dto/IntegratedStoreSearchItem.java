package com.miriyum.domain.store.search.dto;

import com.miriyum.domain.store.core.enums.OperationStatus;
import com.miriyum.domain.store.core.enums.Region;
import java.util.Objects;

/** 통합 검색이 최신 상태를 재검증한 공개 매장 항목이다. */
public record IntegratedStoreSearchItem(
        String storeId,
        String name,
        Region region,
        String address,
        String storeCategoryCode,
        OperationStatus operationStatus,
        PublicStoreModes modes,
        ReservationAvailability reservationAvailability,
        PublicStoreCoordinates coordinates
) {

    public IntegratedStoreSearchItem {
        Objects.requireNonNull(storeId, "storeId is required");
        Objects.requireNonNull(name, "name is required");
        Objects.requireNonNull(region, "region is required");
        Objects.requireNonNull(address, "address is required");
        Objects.requireNonNull(storeCategoryCode, "storeCategoryCode is required");
        Objects.requireNonNull(operationStatus, "operationStatus is required");
        Objects.requireNonNull(modes, "modes is required");
        Objects.requireNonNull(
                reservationAvailability,
                "reservationAvailability is required");
    }
}
