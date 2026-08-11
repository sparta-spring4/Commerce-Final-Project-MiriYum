package com.miriyum.domain.search.dto.contract;

import java.util.Objects;

public record MenuAlternativeAllergenView(String ingredientCode, String disclosureStatus) {
    public MenuAlternativeAllergenView {
        Objects.requireNonNull(ingredientCode, "ingredientCode is required");
        Objects.requireNonNull(disclosureStatus, "disclosureStatus is required");
    }
}
