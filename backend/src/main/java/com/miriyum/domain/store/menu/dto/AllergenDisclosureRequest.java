package com.miriyum.domain.store.menu.dto;

import com.miriyum.domain.store.menu.model.AllergenDisclosureStatus;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** 알레르기 유발 가능 재료 하나의 구조화 입력이다. */
public record AllergenDisclosureRequest(
        @NotBlank @Size(max = 100) String ingredient,
        @NotNull AllergenDisclosureStatus status
) {
}
