package com.miriyum.domain.menu.dto.storeoperator;

import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.entity.MenuVersion;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVisibility;

public record ManagedMenuResponse(
        String menuId,
        String storeId,
        MenuVisibility visibility,
        MenuSellingStatus sellingStatus,
        boolean retired,
        MenuVersionResponse draft,
        MenuVersionResponse scheduled,
        MenuVersionResponse published
) {
    public static ManagedMenuResponse from(Menu menu) {
        return new ManagedMenuResponse(
                String.valueOf(menu.getId()),
                String.valueOf(menu.getStoreId()),
                menu.getVisibility(),
                menu.getSellingStatus(),
                menu.isRetired(),
                response(menu, menu.getDraftVersionNumber()),
                response(menu, menu.getScheduledVersionNumber()),
                response(menu, menu.getPublishedVersionNumber()));
    }

    private static MenuVersionResponse response(Menu menu, Integer number) {
        if (number == null) {
            return null;
        }
        MenuVersion version = menu.getVersions().stream()
                .filter(item -> item.getVersionNumber() == number)
                .findFirst()
                .orElse(null);
        return MenuVersionResponse.from(version);
    }
}
