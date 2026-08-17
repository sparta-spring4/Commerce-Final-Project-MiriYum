package com.miriyum.domain.search.dto.publicapi;

import com.miriyum.domain.menu.enums.MenuSellingStatus;
import java.util.List;

public record PublicMenu(
        String menuId,
        String name,
        String description,
        String imageUrl,
        long price,
        boolean representative,
        String primaryCategoryCode,
        List<String> secondaryCategoryCodes,
        List<String> localTags,
        boolean holdEnabled,
        boolean pickupEnabled,
        MenuSellingStatus saleStatus
) {

    public PublicMenu(
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
        this(
                menuId, name, description, null, price, representative, primaryCategoryCode,
                secondaryCategoryCodes, localTags, holdEnabled, pickupEnabled, saleStatus);
    }

    public PublicMenu withImageUrl(String publicImageUrl) {
        return new PublicMenu(
                menuId, name, description, publicImageUrl, price, representative,
                primaryCategoryCode, secondaryCategoryCodes, localTags, holdEnabled,
                pickupEnabled, saleStatus);
    }
}
