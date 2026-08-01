package com.miriyum.domain.store.menu.model;

import java.util.List;

public record MenuContent(
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
        boolean alcoholic
) {
    public MenuContent {
        secondaryCategoryCodes = List.copyOf(secondaryCategoryCodes);
        localTags = List.copyOf(localTags);
        allergenDisclosures = List.copyOf(allergenDisclosures);
        originDisclosures = List.copyOf(originDisclosures);
    }
}
