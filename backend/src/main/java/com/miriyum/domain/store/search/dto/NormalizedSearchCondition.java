package com.miriyum.domain.store.search.dto;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Objects;

/** RuleInterpreter가 승인한 구조화 조건과 남은 일반 키워드다. */
public record NormalizedSearchCondition(
        List<String> regionCodes,
        List<String> storeCategoryCodes,
        List<String> menuCategoryCodes,
        List<String> tagCodes,
        Long minimumPrice,
        Long maximumPrice,
        Integer partySize,
        LocalDate reservationDate,
        LocalTime reservationTime,
        String remainingKeyword
) {

    public NormalizedSearchCondition {
        regionCodes = List.copyOf(regionCodes);
        storeCategoryCodes = List.copyOf(storeCategoryCodes);
        menuCategoryCodes = List.copyOf(menuCategoryCodes);
        tagCodes = List.copyOf(tagCodes);
        Objects.requireNonNull(remainingKeyword, "remainingKeyword is required");
    }
}
