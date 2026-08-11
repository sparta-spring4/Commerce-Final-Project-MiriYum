package com.miriyum.domain.menu.dto.storeoperator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 재료 하나의 원산지 표시 입력이다. */
public record OriginDisclosureRequest(
        @NotBlank @Size(max = 100) String ingredient,
        @NotBlank @Size(max = 200) String origin
) {
}
