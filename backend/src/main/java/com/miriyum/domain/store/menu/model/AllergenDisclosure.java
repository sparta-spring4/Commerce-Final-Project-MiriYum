package com.miriyum.domain.store.menu.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 운영자가 입력한 알레르기 유발 가능 재료 표시다. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class AllergenDisclosure {

    @Column(name = "ingredient_name", nullable = false, length = 100)
    private String ingredient;

    @Enumerated(EnumType.STRING)
    @Column(name = "disclosure_status", nullable = false, length = 20)
    private AllergenDisclosureStatus status;

    public AllergenDisclosure(String ingredient, AllergenDisclosureStatus status) {
        this.ingredient = ingredient;
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
        return Objects.equals(ingredient, that.ingredient) && status == that.status;
    }

    @Override
    public int hashCode() {
        return Objects.hash(ingredient, status);
    }
}
