package com.miriyum.domain.search.dto.contract;

import java.math.BigDecimal;
import java.util.List;

public record MenuAlternativeSourceView(
        long storeId, String storeName, long menuId, String menuName, int unitPrice,
        String primaryCategoryCode, List<String> secondaryCategoryCodes,
        String allergenInformationStatus, List<MenuAlternativeAllergenView> allergens,
        BigDecimal latitude, BigDecimal longitude, List<String> searchConcepts
) {
    public MenuAlternativeSourceView {
        secondaryCategoryCodes = List.copyOf(secondaryCategoryCodes);
        allergens = List.copyOf(allergens);
        searchConcepts = List.copyOf(searchConcepts);
    }

    public MenuAlternativeSourceView(
            long storeId,
            String storeName,
            long menuId,
            String menuName,
            int unitPrice,
            String primaryCategoryCode,
            List<String> secondaryCategoryCodes,
            String allergenInformationStatus,
            List<MenuAlternativeAllergenView> allergens,
            BigDecimal latitude,
            BigDecimal longitude
    ) {
        this(storeId, storeName, menuId, menuName, unitPrice, primaryCategoryCode,
                secondaryCategoryCodes, allergenInformationStatus, allergens,
                latitude, longitude, List.of());
    }
}
