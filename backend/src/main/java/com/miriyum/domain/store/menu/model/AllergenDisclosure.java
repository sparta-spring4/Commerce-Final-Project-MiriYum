package com.miriyum.domain.store.menu.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 중앙 코드로 식별하는 알레르기 유발 가능 성분 표시다. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllergenDisclosure {

    @Enumerated(EnumType.STRING)
    @Column(name = "allergen_code", nullable = false, length = 30)
    private AllergenIngredientCode ingredientCode;

    @Enumerated(EnumType.STRING)
    @Column(name = "disclosure_status", nullable = false, length = 20)
    private AllergenDisclosureStatus status;

    public AllergenDisclosure(
            AllergenIngredientCode ingredientCode,
            AllergenDisclosureStatus status
    ) {
        this.ingredientCode = ingredientCode;
        this.status = status;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof AllergenDisclosure that)) {
            return false;
        }
        return ingredientCode == that.ingredientCode && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ingredientCode, status);
    }
}
