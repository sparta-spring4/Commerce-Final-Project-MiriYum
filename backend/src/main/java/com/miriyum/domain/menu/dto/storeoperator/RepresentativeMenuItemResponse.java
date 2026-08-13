package com.miriyum.domain.menu.dto.storeoperator;

import com.miriyum.domain.menu.entity.Menu;
import com.miriyum.domain.menu.entity.MenuVersion;
import com.miriyum.domain.menu.enums.MenuSellingStatus;
import com.miriyum.domain.menu.enums.MenuVersionStatus;

public record RepresentativeMenuItemResponse(
        String menuId,
        int displayOrder,
        int publishedVersionNumber,
        String name,
        int price,
        MenuSellingStatus sellingStatus
) {
    public static RepresentativeMenuItemResponse from(Menu menu, int displayOrder) {
        MenuVersion version = menu.getVersions().stream()
                .filter(candidate -> candidate.getVersionNumber()
                        == menu.getPublishedVersionNumber())
                .filter(candidate -> candidate.getStatus() == MenuVersionStatus.PUBLISHED)
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "representative menu must have a current published version"));
        return new RepresentativeMenuItemResponse(
                String.valueOf(menu.getId()),
                displayOrder,
                version.getVersionNumber(),
                version.getName(),
                version.getPrice(),
                menu.getSellingStatus());
    }
}
