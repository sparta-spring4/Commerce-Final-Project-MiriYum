package com.miriyum.domain.alternative.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.alternative.model.AlternativeMenuCandidate;
import com.miriyum.domain.alternative.model.AlternativeMenuSource;
import com.miriyum.domain.alternative.model.MenuAlternativeRankingReason;
import java.util.List;
import org.junit.jupiter.api.Test;

class MenuAlternativeScorerTest {

    private final MenuAlternativeScorer scorer = new MenuAlternativeScorer();
    private final AlternativeMenuSource source = new AlternativeMenuSource(
            1L, 10L, 14_000, "MEAT", List.of("SPICY", "CHICKEN"));

    @Test
    void prioritizesLlmConceptRelevanceBeforeSmallPriceDifferences() {
        var chicken = scorer.score(source, candidate(11L, 14_500, 50, List.of("CHICKEN")));
        var pork = scorer.score(source, candidate(12L, 14_000, 0, List.of("SPICY")));

        assertThat(chicken.totalScore()).isGreaterThan(pork.totalScore());
        assertThat(chicken.rankingReason()).isEqualTo(MenuAlternativeRankingReason.LLM_CONCEPT);
    }

    @Test
    void combinesConceptSecondaryCategoryAndPriceIntoExplainableHundredPointScore() {
        var exact = scorer.score(source,
                candidate(11L, 14_000, 50, List.of("SPICY", "CHICKEN")));
        var boundary = scorer.score(source,
                candidate(12L, 16_800, 0, List.of()));

        assertThat(exact.totalScore()).isEqualTo(100);
        assertThat(exact.conceptScore()).isEqualTo(50);
        assertThat(exact.secondaryCategoryScore()).isEqualTo(20);
        assertThat(exact.priceSimilarityScore()).isEqualTo(30);
        assertThat(boundary.totalScore()).isZero();
    }

    private static AlternativeMenuCandidate candidate(long menuId, int price,
            int conceptScore, List<String> secondaryCategories) {
        return new AlternativeMenuCandidate(1L, "매장", menuId, "메뉴", price, "MEAT",
                secondaryCategories, "REGISTERED", List.of(), null, null, null,
                conceptScore);
    }
}
