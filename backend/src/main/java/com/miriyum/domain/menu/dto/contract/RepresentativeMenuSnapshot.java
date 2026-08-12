package com.miriyum.domain.menu.dto.contract;

import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import java.util.List;

public record RepresentativeMenuSnapshot(
        String storeId,
        long version,
        RepresentativeMenuSettingStatus status,
        List<RepresentativeMenuItem> items
) {
    public RepresentativeMenuSnapshot {
        items = List.copyOf(items);
    }

    public static RepresentativeMenuSnapshot unconfigured(long storeId) {
        return new RepresentativeMenuSnapshot(
                String.valueOf(storeId), 0L,
                RepresentativeMenuSettingStatus.UNCONFIGURED, List.of());
    }
}
