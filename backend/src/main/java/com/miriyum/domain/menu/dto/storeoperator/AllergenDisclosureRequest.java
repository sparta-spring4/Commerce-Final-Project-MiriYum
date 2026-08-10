package com.miriyum.domain.menu.dto.storeoperator;

import com.miriyum.domain.menu.model.AllergenIngredientCode;
import com.miriyum.domain.menu.model.AllergenDisclosureStatus;
import jakarta.validation.constraints.NotNull;

/** 알레르기 유발 가능 재료 하나의 구조화 입력이다. */
public record AllergenDisclosureRequest(
        @NotNull AllergenIngredientCode ingredientCode,
        @NotNull AllergenDisclosureStatus status
) {
}
