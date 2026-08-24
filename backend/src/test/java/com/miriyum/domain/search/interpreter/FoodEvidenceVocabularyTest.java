package com.miriyum.domain.search.interpreter;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.search.expansion.StructuredFoodEvidence.Dimension;
import com.miriyum.domain.search.expansion.StructuredFoodEvidenceSource;
import org.junit.jupiter.api.Test;

class FoodEvidenceVocabularyTest {

    private final FoodEvidenceVocabulary vocabulary = new FoodEvidenceVocabulary();

    @Test
    void resolvesReviewedMenuAliasesToOneBaseFamily() {
        assertThat(vocabulary.resolve(
                        Dimension.MENU_FAMILY,
                        "뼈다귀 해장국",
                        StructuredFoodEvidenceSource.DETERMINISTIC))
                .get()
                .satisfies(term -> {
                    assertThat(term.id()).isEqualTo("BONE_HANGOVER_SOUP");
                    assertThat(term.surface()).isEqualTo("뼈다귀 해장국");
                    assertThat(term.matchTerms())
                            .contains("뼈해장국", "뼈다귀 해장국");
                });
        assertThat(vocabulary.resolve(
                        Dimension.MENU_FAMILY,
                        "멸치국수",
                        StructuredFoodEvidenceSource.DETERMINISTIC))
                .get()
                .extracting(term -> term.id())
                .isEqualTo("BANQUET_NOODLES");
    }

    @Test
    void rejectsUnknownTermsAndExposesTheReviewedVocabularyVersion() {
        assertThat(vocabulary.resolve(
                Dimension.TASTE,
                "존재하지않는맛",
                StructuredFoodEvidenceSource.LLM)).isEmpty();
        assertThat(vocabulary.version()).isEqualTo("food-evidence-v1");
    }
}
