package com.miriyum.domain.search.expansion;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/** 검증된 검색 개념과 제공자 사용량이다. */
public record SearchConceptExpansion(
        List<String> concepts,
        long inputTokens,
        long outputTokens
) {

    private static final int MAX_CONCEPT_LENGTH = 60;

    public SearchConceptExpansion {
        if (concepts == null || inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("concepts and non-negative usage are required");
        }
        List<String> normalized = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (String value : concepts) {
            if (value == null) {
                continue;
            }
            String concept = value.trim().replaceAll("\\s+", " ");
            if (concept.isBlank() || concept.length() > MAX_CONCEPT_LENGTH) {
                continue;
            }
            if (seen.add(concept.toLowerCase(Locale.ROOT))) {
                normalized.add(concept);
            }
        }
        concepts = List.copyOf(normalized);
    }

    public static SearchConceptExpansion empty() {
        return new SearchConceptExpansion(List.of(), 0, 0);
    }
}
