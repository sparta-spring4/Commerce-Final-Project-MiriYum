package com.miriyum.domain.search.dto.publicapi;

import com.miriyum.domain.store.enums.OperationStatus;
import com.miriyum.domain.store.enums.Region;
import java.util.List;

public record PublicStoreDetail(
        String storeId,
        String name,
        String description,
        Region region,
        String address,
        String timeZoneId,
        String storeCategoryCode,
        List<String> tags,
        OperationStatus operationStatus,
        PublicStoreModes modes,
        List<PublicDailySchedule> operatingHours,
        List<PublicDailyTimeSlots> reservationTimeSlots,
        List<PublicMenu> representativeMenus,
        ReservationAvailability reservationAvailability
) {
}
