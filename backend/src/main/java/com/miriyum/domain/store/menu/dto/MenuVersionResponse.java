package com.miriyum.domain.store.menu.dto;

import com.miriyum.domain.store.menu.entity.MenuVersion;
import com.miriyum.domain.store.menu.enums.MenuVersionStatus;
import com.miriyum.domain.store.menu.model.AllergenDisclosure;
import com.miriyum.domain.store.menu.model.DisclosureRegistrationStatus;
import com.miriyum.domain.store.menu.model.OriginDisclosure;
import java.time.Instant;
import java.util.List;

public record MenuVersionResponse(
        int versionNumber,
        MenuVersionStatus status,
        String name,
        String description,
        int price,
        boolean representative,
        String primaryCategoryCode,
        List<String> secondaryCategoryCodes,
        List<String> localTags,
        boolean holdSelectionAllowed,
        boolean pickupSelectionAllowed,
        DisclosureRegistrationStatus allergenInformationStatus,
        List<AllergenDisclosure> allergenDisclosures,
        DisclosureRegistrationStatus originInformationStatus,
        List<OriginDisclosure> originDisclosures,
        boolean alcoholic,
        Instant effectiveAt
) {
    public static MenuVersionResponse from(MenuVersion version) {
        if (version == null) {
            return null;
        }
        return new MenuVersionResponse(
                version.getVersionNumber(),
                version.getStatus(),
                version.getName(),
                version.getDescription(),
                version.getPrice(),
                version.isRepresentative(),
                version.getPrimaryCategoryCode(),
                List.copyOf(version.getSecondaryCategoryCodes()),
                List.copyOf(version.getLocalTags()),
                version.isHoldSelectionAllowed(),
                version.isPickupSelectionAllowed(),
                version.getAllergenInformationStatus(),
                List.copyOf(version.getAllergenDisclosures()),
                version.getOriginInformationStatus(),
                List.copyOf(version.getOriginDisclosures()),
                version.isAlcoholic(),
                version.getEffectiveAt());
    }
}
