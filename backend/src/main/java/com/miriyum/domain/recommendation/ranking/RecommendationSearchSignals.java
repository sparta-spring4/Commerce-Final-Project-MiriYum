package com.miriyum.domain.recommendation.ranking;

import java.util.List;

public record RecommendationSearchSignals(
        List<String> storeCategoryCodes,
        List<String> menuCategoryCodes,
        List<String> tagCodes
) {

    public RecommendationSearchSignals {
        storeCategoryCodes = List.copyOf(storeCategoryCodes);
        menuCategoryCodes = List.copyOf(menuCategoryCodes);
        tagCodes = List.copyOf(tagCodes);
    }
}
