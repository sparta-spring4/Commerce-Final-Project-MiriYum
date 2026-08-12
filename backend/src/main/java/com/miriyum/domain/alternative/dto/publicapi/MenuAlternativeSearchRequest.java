package com.miriyum.domain.alternative.dto.publicapi;

import com.miriyum.domain.alternative.model.MenuAlternativeSearchCommand;
import com.miriyum.domain.menu.model.AllergenIngredientCode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.Set;

public record MenuAlternativeSearchRequest(
        @Min(1) int quantity,
        @NotNull LocalDate serviceDate,
        @NotNull LocalTime startTime,
        @Pattern(regexp = "^(?:[+-](?:0\\d|1[0-7]):[0-5]\\d|[+-]18:00)$") String startOffset,
        @Min(1) @Max(100) int partySize,
        Boolean includesInfants,
        Set<AllergenIngredientCode> excludedAllergenCodes,
        @Min(1) @Max(20) Integer size
) {
    @AssertTrue(message = "startTime must use minute precision")
    public boolean isMinutePrecision() {
        return startTime == null || (startTime.getSecond() == 0 && startTime.getNano() == 0);
    }

    public MenuAlternativeSearchCommand toCommand() {
        return new MenuAlternativeSearchCommand(quantity, serviceDate, startTime,
                startOffset == null ? null : ZoneOffset.of(startOffset), partySize,
                Boolean.TRUE.equals(includesInfants), excludedAllergenCodes == null
                ? Set.of() : excludedAllergenCodes.stream().map(Enum::name)
                        .collect(java.util.stream.Collectors.toUnmodifiableSet()),
                size == null ? 10 : size);
    }
}
