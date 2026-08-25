package com.miriyum.domain.search.entity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatIllegalArgumentException;

import java.math.BigDecimal;
import java.time.Instant;
import org.junit.jupiter.api.Test;

class MenuSearchProfileTest {

    private static final Instant NOW = Instant.parse("2026-08-25T00:00:00Z");

    @Test
    void normalizesTermAndPreservesItsEvidenceMetadata() {
        MenuSearchProfile profile = MenuSearchProfile.create(
                101L, "food-profile-v1", NOW);

        MenuSearchProfileTerm term = profile.addTerm(
                MenuSearchProfileDimension.TASTE,
                "  칼칼한   맛  ",
                new BigDecimal("0.9500"),
                MenuSearchProfileSource.CURATED,
                NOW);

        assertThat(term.getNormalizedTerm()).isEqualTo("칼칼한 맛");
        assertThat(term.getDimension()).isEqualTo(MenuSearchProfileDimension.TASTE);
        assertThat(term.getConfidence()).isEqualByComparingTo("0.9500");
        assertThat(term.getSource()).isEqualTo(MenuSearchProfileSource.CURATED);
    }

    @Test
    void rejectsConfidenceOutsideInclusiveUnitRange() {
        MenuSearchProfile profile = MenuSearchProfile.create(
                101L, "food-profile-v1", NOW);

        assertThatIllegalArgumentException().isThrownBy(() -> profile.addTerm(
                MenuSearchProfileDimension.AROMA,
                "불향",
                new BigDecimal("1.0001"),
                MenuSearchProfileSource.RULE_DERIVED,
                NOW));
    }

    @Test
    void rejectsDuplicateDimensionAndNormalizedTerm() {
        MenuSearchProfile profile = MenuSearchProfile.create(
                101L, "food-profile-v1", NOW);
        profile.addTerm(
                MenuSearchProfileDimension.INGREDIENT,
                "해물",
                BigDecimal.ONE,
                MenuSearchProfileSource.CURATED,
                NOW);

        assertThatIllegalArgumentException().isThrownBy(() -> profile.addTerm(
                MenuSearchProfileDimension.INGREDIENT,
                " 해물 ",
                new BigDecimal("0.9000"),
                MenuSearchProfileSource.LLM_DERIVED,
                NOW));
    }
}
