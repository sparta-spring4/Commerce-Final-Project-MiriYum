package com.miriyum.domain.search.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class StructuredSearchRelevanceTest {

    @Test
    void higherFoodEvidenceAlwaysPrecedesHigherLegacyTier() {
        StructuredSearchRelevance strongerFood = StructuredSearchRelevance.of(20, 1);
        StructuredSearchRelevance strongerLegacy = StructuredSearchRelevance.of(10, 4);

        assertThat(strongerFood.compareTo(strongerLegacy)).isLessThan(0);
    }

    @Test
    void legacyTierBreaksTiesOnlyInsideTheSameFoodEvidenceGroup() {
        StructuredSearchRelevance higherLegacy = StructuredSearchRelevance.of(20, 4);
        StructuredSearchRelevance lowerLegacy = StructuredSearchRelevance.of(20, 1);

        assertThat(higherLegacy.compareTo(lowerLegacy)).isLessThan(0);
        assertThat(higherLegacy.sameGroup(StructuredSearchRelevance.of(20, 4))).isTrue();
        assertThat(higherLegacy.sameGroup(lowerLegacy)).isFalse();
    }

    @Test
    void rejectsValuesOutsideRepositoryScoreBounds() {
        assertThat(StructuredSearchRelevance.of(224, 4).structuredRelevance())
                .isEqualTo(224);
        assertThatThrownBy(() -> StructuredSearchRelevance.of(225, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> StructuredSearchRelevance.of(0, 5))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
