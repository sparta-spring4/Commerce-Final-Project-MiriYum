package com.miriyum.domain.menu.dto.storeoperator;

import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import java.util.List;

public record RepresentativeMenuSettingResponse(
        long version,
        RepresentativeMenuSettingStatus status,
        List<RepresentativeMenuItemResponse> items
) {
    public RepresentativeMenuSettingResponse {
        items = List.copyOf(items);
    }

    public static RepresentativeMenuSettingResponse unconfigured() {
        return new RepresentativeMenuSettingResponse(
                0L, RepresentativeMenuSettingStatus.UNCONFIGURED, List.of());
    }
}
