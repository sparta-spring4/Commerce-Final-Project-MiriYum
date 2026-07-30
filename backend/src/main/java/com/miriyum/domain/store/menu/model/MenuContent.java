package com.miriyum.domain.store.menu.model;

import java.util.List;

public record MenuContent(
        String name,
        String description,
        int price,
        boolean representative,
        String primaryCategoryCode,
        List<String> secondaryCategoryCodes,
        List<String> localTags,
        boolean holdSelectionAllowed,
        boolean pickupSelectionAllowed
) {
    public MenuContent {
        secondaryCategoryCodes = List.copyOf(secondaryCategoryCodes);
        localTags = List.copyOf(localTags);
    }
}
