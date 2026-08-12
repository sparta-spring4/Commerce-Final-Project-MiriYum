package com.miriyum.domain.alternative.model;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;

public record MenuAlternativeSearchCommand(int quantity, LocalDate serviceDate,
        LocalTime startTime, ZoneOffset startOffset, int partySize, boolean includesInfants,
        Set<String> excludedAllergenCodes, int size) {
    public MenuAlternativeSearchCommand {
        excludedAllergenCodes = Set.copyOf(excludedAllergenCodes);
    }
}
