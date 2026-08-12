package com.miriyum.domain.menu.model;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/** 운영자가 입력한 재료별 원산지 표시다. */
@Embeddable
@Getter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class OriginDisclosure {

    @Column(name = "ingredient_name", nullable = false, length = 100)
    private String ingredient;

    @Column(name = "origin_label", nullable = false, length = 200)
    private String origin;

    public OriginDisclosure(String ingredient, String origin) {
        this.ingredient = ingredient;
        this.origin = origin;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof OriginDisclosure that)) {
            return false;
        }
        return Objects.equals(ingredient, that.ingredient)
                && Objects.equals(origin, that.origin);
    }

    @Override
    public int hashCode() {
        return Objects.hash(ingredient, origin);
    }
}
