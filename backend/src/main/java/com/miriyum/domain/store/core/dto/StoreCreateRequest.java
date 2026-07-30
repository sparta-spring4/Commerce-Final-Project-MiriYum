package com.miriyum.domain.store.core.dto;

import com.miriyum.domain.store.core.enums.BusinessType;
import com.miriyum.domain.store.core.enums.Region;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record StoreCreateRequest(
        @NotBlank
        @Pattern(regexp = "^[0-9]{10}$")
        String businessRegistrationNumber,

        @NotNull
        BusinessType businessType,

        @NotBlank
        @Size(max = 100)
        String name,

        @NotNull
        @Size(max = 1000)
        String description,

        @NotNull
        Region region,

        @NotBlank
        @Size(max = 300)
        String address,

        @NotBlank
        @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$")
        String storeCategoryCode,

        @NotNull
        @Size(max = 20)
        List<
                @NotBlank
                @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$")
                String> tagCodes,

        @NotNull
        @Valid
        StoreModesRequest modes
) {
}
