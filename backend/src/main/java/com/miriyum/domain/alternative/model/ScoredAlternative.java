package com.miriyum.domain.alternative.model;

import java.util.Objects;

public record ScoredAlternative(
        EligibleAlternative eligible,
        MenuAlternativeScore score
) {
    public ScoredAlternative {
        Objects.requireNonNull(eligible, "eligible is required");
        Objects.requireNonNull(score, "score is required");
    }
}
