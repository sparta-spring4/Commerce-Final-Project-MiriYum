package com.miriyum.domain.menu.dto.contract;

import com.miriyum.domain.menu.enums.MenuSellingStatus;

public record RepresentativeMenuItem(
        String menuId,
        int displayOrder,
        int publishedVersionNumber,
        String name,
        int price,
        MenuSellingStatus sellingStatus
) {
}
