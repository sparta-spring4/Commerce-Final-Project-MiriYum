package com.miriyum.domain.menu.dto.storeoperator;

import com.miriyum.domain.menu.model.DisclosureRegistrationStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

public record MenuContentRequest(
        @NotBlank @Size(max = 100) String name,
        @NotNull @Size(max = 1000) String description,
        @Min(0) @Max(2_000_000_000) int price,
        boolean representative,
        @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String primaryCategoryCode,
        @NotNull @Size(max = 5) List<
                @NotBlank @Pattern(regexp = "^[A-Z][A-Z0-9_]{1,49}$") String>
                secondaryCategoryCodes,
        @NotNull @Size(max = 10) List<@NotBlank @Size(max = 30) String> localTags,
        boolean holdSelectionAllowed,
        boolean pickupSelectionAllowed,
        @NotNull DisclosureRegistrationStatus allergenInformationStatus,
        @NotNull @Size(max = 20) List<@NotNull @Valid AllergenDisclosureRequest>
                allergenDisclosures,
        @NotNull DisclosureRegistrationStatus originInformationStatus,
        @NotNull @Size(max = 20) List<@NotNull @Valid OriginDisclosureRequest>
                originDisclosures,
        @NotNull Boolean alcoholic
) {
    public MenuContentRequest {
        secondaryCategoryCodes = secondaryCategoryCodes == null
                ? null : List.copyOf(secondaryCategoryCodes);
        localTags = localTags == null ? null : List.copyOf(localTags);
        allergenDisclosures = allergenDisclosures == null
                ? null : List.copyOf(allergenDisclosures);
        originDisclosures = originDisclosures == null
                ? null : List.copyOf(originDisclosures);
    }
}
