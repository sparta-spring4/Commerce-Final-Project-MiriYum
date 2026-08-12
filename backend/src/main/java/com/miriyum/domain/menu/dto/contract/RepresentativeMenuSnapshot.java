package com.miriyum.domain.menu.dto.contract;

import com.miriyum.domain.menu.enums.RepresentativeMenuSettingStatus;
import java.util.List;

public record RepresentativeMenuSnapshot(
        long version,
        RepresentativeMenuSettingStatus status,
        List<RepresentativeMenuItem> items
) {
    public RepresentativeMenuSnapshot {
        items = List.copyOf(items);
    }

    public static RepresentativeMenuSnapshot unconfigured() {
        return new RepresentativeMenuSnapshot(
                0L, RepresentativeMenuSettingStatus.UNCONFIGURED, List.of());
    }
}
