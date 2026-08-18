package com.miriyum.domain.alternative.service;

import com.miriyum.domain.alternative.model.AlternativeMenuCandidate;
import com.miriyum.domain.alternative.model.AlternativeMenuSource;
import com.miriyum.domain.alternative.model.MenuAlternativeRankingReason;
import com.miriyum.domain.alternative.model.MenuAlternativeScore;
import java.util.HashSet;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public class MenuAlternativeScorer {

    public MenuAlternativeScore score(
            AlternativeMenuSource source,
            AlternativeMenuCandidate candidate
    ) {
        int concept = candidate.conceptScore();
        Set<String> secondary = new HashSet<>(source.secondaryCategoryCodes());
        secondary.retainAll(new HashSet<>(candidate.secondaryCategoryCodes()));
        int secondaryCategory = Math.min(secondary.size(), 2) * 10;
        int priceSimilarity = priceSimilarity(source.unitPrice(), candidate.unitPrice());
        int total = concept + secondaryCategory + priceSimilarity;
        return new MenuAlternativeScore(concept, secondaryCategory, priceSimilarity, total,
                highestReason(concept, secondaryCategory, priceSimilarity));
    }

    private static int priceSimilarity(int sourcePrice, int candidatePrice) {
        long boundary = (long) sourcePrice * 20L;
        long scaledDifference = (long) Math.abs(candidatePrice - sourcePrice) * 100L;
        if (boundary == 0L || scaledDifference >= boundary) {
            return 0;
        }
        return (int) ((boundary - scaledDifference) * 30L / boundary);
    }

    private static MenuAlternativeRankingReason highestReason(
            int concept,
            int secondaryCategory,
            int priceSimilarity
    ) {
        if (concept >= secondaryCategory && concept >= priceSimilarity && concept > 0) {
            return MenuAlternativeRankingReason.LLM_CONCEPT;
        }
        if (secondaryCategory >= priceSimilarity && secondaryCategory > 0) {
            return MenuAlternativeRankingReason.SECONDARY_CATEGORY;
        }
        return MenuAlternativeRankingReason.PRICE_SIMILARITY;
    }
}
