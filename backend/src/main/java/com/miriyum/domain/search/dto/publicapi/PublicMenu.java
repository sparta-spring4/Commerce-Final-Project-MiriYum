package com.miriyum.domain.search.dto.publicapi;

import com.miriyum.domain.menu.enums.MenuSellingStatus;
import java.util.List;

public record PublicMenu(
        String menuId,
        String name,
        String description,
        long price,
        boolean representative,
        String primaryCategoryCode,
        List<String> secondaryCategoryCodes,
        List<String> localTags,
        boolean holdEnabled,
        boolean pickupEnabled,
        MenuSellingStatus saleStatus
) {
}
