package com.miriyum.domain.store.recommendation.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class HistoryRecommendationRankerTest {

    private static final Instant AS_OF = Instant.parse("2026-08-06T06:00:00Z");
    private final HistoryRecommendationRanker ranker = new HistoryRecommendationRanker();

    @Test
    void capsIntentAtSeventyAndHistoryAtThirty() {
        RecommendationCandidate candidate = candidate(
                1L, 4, true, true, 5, 8,
                RecommendationAvailability.AVAILABLE, null, Set.of(101L));
        RecommendationHistorySnapshot history = new RecommendationHistorySnapshot(List.of(
                event(11L, 1L, 10, Set.of(101L)),
                event(12L, 1L, 20, Set.of(101L)),
                event(13L, 1L, 25, Set.of(101L))));

        RankedRecommendation result = ranker.rank(List.of(candidate), history, AS_OF).getFirst();

        assertThat(result.intentScore()).isEqualTo(70);
        assertThat(result.historyScore()).isEqualTo(30);
        assertThat(result.totalScore()).isEqualTo(100);
    }

    @Test
    void strongerCurrentIntentBeatsMaximumHistory() {
        RecommendationCandidate strongIntent = candidate(
                2L, 4, true, true, 2, 3,
                RecommendationAvailability.AVAILABLE, null, Set.of());
        RecommendationCandidate familiar = candidate(
                1L, 4, false, false, 0, 0,
                RecommendationAvailability.UNAVAILABLE, null, Set.of(101L));
        RecommendationHistorySnapshot history = new RecommendationHistorySnapshot(List.of(
                event(11L, 1L, 10, Set.of(101L)),
                event(12L, 1L, 20, Set.of(101L))));

        List<RankedRecommendation> ranked = ranker.rank(
                List.of(familiar, strongIntent), history, AS_OF);

        assertThat(ranked).extracting(result -> result.candidate().storeId())
                .containsExactly(2L, 1L);
        assertThat(ranked).extracting(RankedRecommendation::totalScore)
                .containsExactly(70, 62);
    }

    @Test
    void appliesOneInclusiveRecencyBucketPerEvent() {
        RecommendationCandidate candidate = candidate(
                1L, 0, false, false, 0, 0,
                RecommendationAvailability.UNAVAILABLE, null, Set.of(101L));
        RecommendationHistorySnapshot history = new RecommendationHistorySnapshot(List.of(
                event(11L, 1L, 30, Set.of(101L)),
                event(12L, 1L, 90, Set.of()),
                event(13L, 1L, 180, Set.of()),
                event(14L, 1L, 181, Set.of(101L)),
                new RecommendationHistoryEvent(
                        15L, 1L, AS_OF.plus(1, ChronoUnit.SECONDS), Set.of(101L))));

        RankedRecommendation result = ranker.rank(List.of(candidate), history, AS_OF).getFirst();

        assertThat(result.historyScore()).isEqualTo(24);
        assertThat(result.reason()).isEqualTo(RecommendationReason.VISITED_STORE);
    }

    @Test
    void scoresAtMostOncePerReservationWhenSeveralMenusMatch() {
        RecommendationCandidate candidate = candidate(
                1L, 0, false, false, 0, 0,
                RecommendationAvailability.UNAVAILABLE, null, Set.of(101L, 102L));
        RecommendationHistorySnapshot history = new RecommendationHistorySnapshot(List.of(
                event(11L, 9L, 10, Set.of(101L, 102L))));

        RankedRecommendation result = ranker.rank(List.of(candidate), history, AS_OF).getFirst();

        assertThat(result.historyScore()).isEqualTo(6);
        assertThat(result.reason()).isEqualTo(RecommendationReason.ORDERED_MENU);
    }

    @Test
    void appliesEveryTieBreakerInTheApprovedOrder() {
        assertOrder(
                candidate(2L, 4, false, false, 0, 0,
                        RecommendationAvailability.UNAVAILABLE, null, Set.of()),
                candidate(1L, 3, false, false, 0, 0,
                        RecommendationAvailability.UNAVAILABLE, null, Set.of()),
                RecommendationHistorySnapshot.empty(),
                2L, 1L);

        assertOrder(
                candidate(2L, 0, true, false, 0, 0,
                        RecommendationAvailability.UNAVAILABLE, null, Set.of()),
                candidate(1L, 0, false, true, 0, 0,
                        RecommendationAvailability.AVAILABLE, null, Set.of()),
                RecommendationHistorySnapshot.empty(),
                2L, 1L);

        assertOrder(
                candidate(2L, 0, false, false, 2, 0,
                        RecommendationAvailability.UNAVAILABLE, null, Set.of()),
                candidate(1L, 0, false, false, 1, 2,
                        RecommendationAvailability.UNAVAILABLE, null, Set.of()),
                RecommendationHistorySnapshot.empty(),
                2L, 1L);

        RecommendationHistorySnapshot availabilityTieHistory =
                new RecommendationHistorySnapshot(List.of(event(31L, 1L, 180, Set.of())));
        assertOrder(
                candidate(2L, 1, false, false, 0, 1,
                        RecommendationAvailability.AVAILABLE, null, Set.of()),
                candidate(1L, 1, false, false, 0, 0,
                        RecommendationAvailability.NOT_REQUESTED, null, Set.of()),
                availabilityTieHistory,
                2L, 1L);

        assertOrder(
                candidate(2L, 1, false, false, 0, 0,
                        RecommendationAvailability.UNAVAILABLE,
                        new BigDecimal("100.00"), Set.of()),
                candidate(1L, 1, false, false, 0, 0,
                        RecommendationAvailability.UNAVAILABLE,
                        new BigDecimal("200.00"), Set.of()),
                RecommendationHistorySnapshot.empty(),
                2L, 1L);

        assertOrder(
                candidate(2L, 1, false, false, 0, 0,
                        RecommendationAvailability.UNAVAILABLE,
                        new BigDecimal("200.00"), Set.of()),
                candidate(1L, 1, false, false, 0, 0,
                        RecommendationAvailability.UNAVAILABLE,
                        new BigDecimal("200.00"), Set.of()),
                RecommendationHistorySnapshot.empty(),
                1L, 2L);
    }

    @Test
    void selectsTheHighestActualContributionWithTheApprovedTiePriority() {
        RecommendationCandidate candidate = candidate(
                1L, 2, true, true, 2, 3,
                RecommendationAvailability.AVAILABLE, null, Set.of(101L));
        RecommendationHistorySnapshot history = new RecommendationHistorySnapshot(List.of(
                event(11L, 1L, 10, Set.of(101L))));

        RankedRecommendation result = ranker.rank(List.of(candidate), history, AS_OF).getFirst();

        assertThat(result.reason()).isEqualTo(RecommendationReason.KEYWORD);
        assertThat(result.reason().code()).isEqualTo("KEYWORD_MATCH");
        assertThat(result.reason().message()).isNotBlank();
    }

    @Test
    void returnsNoReasonWhenNoFactorContributed() {
        RecommendationCandidate candidate = candidate(
                1L, 0, false, false, 0, 0,
                RecommendationAvailability.UNAVAILABLE, null, Set.of());

        RankedRecommendation result = ranker.rank(
                List.of(candidate), RecommendationHistorySnapshot.empty(), AS_OF).getFirst();

        assertThat(result.totalScore()).isZero();
        assertThat(result.reason()).isNull();
    }

    @Test
    void rejectsInvalidCandidateInputs() {
        assertThatThrownBy(() -> candidate(
                0L, 0, false, false, 0, 0,
                RecommendationAvailability.UNAVAILABLE, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(
                1L, 5, false, false, 0, 0,
                RecommendationAvailability.UNAVAILABLE, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(
                1L, 0, false, false, -1, 0,
                RecommendationAvailability.UNAVAILABLE, null, Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> candidate(
                1L, 0, false, false, 0, 0,
                RecommendationAvailability.UNAVAILABLE, new BigDecimal("-0.01"), Set.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static RecommendationCandidate candidate(
            long storeId,
            int relevanceTier,
            boolean storeCategoryMatch,
            boolean menuPrimaryCategoryMatch,
            int secondaryMatches,
            int tagMatches,
            RecommendationAvailability availability,
            BigDecimal distanceMeters,
            Set<Long> currentMenuIds
    ) {
        return new RecommendationCandidate(
                storeId,
                relevanceTier,
                storeCategoryMatch,
                menuPrimaryCategoryMatch,
                secondaryMatches,
                tagMatches,
                availability,
                distanceMeters,
                currentMenuIds);
    }

    private void assertOrder(
            RecommendationCandidate first,
            RecommendationCandidate second,
            RecommendationHistorySnapshot history,
            long expectedFirst,
            long expectedSecond
    ) {
        assertThat(ranker.rank(List.of(second, first), history, AS_OF))
                .extracting(result -> result.candidate().storeId())
                .containsExactly(expectedFirst, expectedSecond);
    }

    private static RecommendationHistoryEvent event(
            long reservationId,
            long storeId,
            long daysAgo,
            Set<Long> menuIds
    ) {
        return new RecommendationHistoryEvent(
                reservationId,
                storeId,
                AS_OF.minus(daysAgo, ChronoUnit.DAYS),
                menuIds);
    }
}
