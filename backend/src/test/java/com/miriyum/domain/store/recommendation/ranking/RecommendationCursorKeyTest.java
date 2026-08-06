package com.miriyum.domain.store.recommendation.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Set;
import org.junit.jupiter.api.Test;

class RecommendationCursorKeyTest {

    @Test
    void roundTripsEveryApprovedTieField() {
        RankedRecommendation result = ranked(
                17L, 88, true, 2, RecommendationAvailability.AVAILABLE,
                new BigDecimal("123.4500"));

        RecommendationCursorKey key = RecommendationCursorKey.from(result);
        RecommendationCursorKey decoded = RecommendationCursorKey.parse(
                key.serialize(), result.candidate().storeId());

        assertThat(decoded).isEqualTo(new RecommendationCursorKey(
                88, true, 2, 2, new BigDecimal("123.45"), 17L));
    }

    @Test
    void treatsOnlyCandidatesAfterTheCursorAsNextPageCandidates() {
        RecommendationCursorKey cursor = RecommendationCursorKey.from(ranked(
                10L, 50, false, 1, RecommendationAvailability.NOT_REQUESTED, null));

        assertThat(cursor.isAfter(ranked(
                11L, 50, false, 1, RecommendationAvailability.NOT_REQUESTED, null)))
                .isTrue();
        assertThat(cursor.isAfter(ranked(
                9L, 50, false, 1, RecommendationAvailability.NOT_REQUESTED, null)))
                .isFalse();
        assertThat(cursor.isAfter(ranked(
                12L, 49, true, 5, RecommendationAvailability.AVAILABLE, null)))
                .isTrue();
    }

    @Test
    void rejectsMalformedOrUnsafeCursorValues() {
        assertThatThrownBy(() -> RecommendationCursorKey.parse("1|true|2|2", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecommendationCursorKey.parse("-1|true|2|2|~", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecommendationCursorKey.parse("1|yes|2|2|~", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecommendationCursorKey.parse("1|true|2|2|NaN", 1L))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> RecommendationCursorKey.parse("1|true|2|2|~", 0L))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RankedRecommendation ranked(
            long storeId,
            int totalScore,
            boolean storeCategoryMatch,
            int secondaryMatches,
            RecommendationAvailability availability,
            BigDecimal distance
    ) {
        RecommendationCandidate candidate = new RecommendationCandidate(
                storeId,
                0,
                storeCategoryMatch,
                false,
                secondaryMatches,
                0,
                availability,
                distance,
                Set.of());
        int intentScore = Math.min(70, totalScore);
        int historyScore = totalScore - intentScore;
        return new RankedRecommendation(
                candidate, intentScore, historyScore, totalScore, null);
    }
}
