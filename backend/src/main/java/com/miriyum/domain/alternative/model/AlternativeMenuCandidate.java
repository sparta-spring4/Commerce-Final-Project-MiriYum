package com.miriyum.domain.alternative.model;

import com.miriyum.domain.search.dto.contract.MenuAlternativeAllergenView;
import java.math.BigDecimal;
import java.util.List;

public record AlternativeMenuCandidate(long storeId, String storeName, long menuId,
        String menuName, int unitPrice, String primaryCategoryCode,
        List<String> secondaryCategoryCodes, String allergenInformationStatus,
        List<MenuAlternativeAllergenView> allergens, BigDecimal latitude,
        BigDecimal longitude, BigDecimal distanceMeters, int conceptScore) {
    public AlternativeMenuCandidate {
        secondaryCategoryCodes = List.copyOf(secondaryCategoryCodes);
        allergens = List.copyOf(allergens);
        if (conceptScore < 0 || conceptScore > 50) {
            throw new IllegalArgumentException("conceptScore must be between 0 and 50");
        }
    }

    public AlternativeMenuCandidate(long storeId, String storeName, long menuId,
            String menuName, int unitPrice, String primaryCategoryCode,
            List<String> secondaryCategoryCodes, String allergenInformationStatus,
            List<MenuAlternativeAllergenView> allergens, BigDecimal latitude,
            BigDecimal longitude, BigDecimal distanceMeters) {
        this(storeId, storeName, menuId, menuName, unitPrice, primaryCategoryCode,
                secondaryCategoryCodes, allergenInformationStatus, allergens, latitude,
                longitude, distanceMeters, 0);
    }
}
