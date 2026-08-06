package com.miriyum.domain.store.recommendation.ranking;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.interpreter.InterpretedSearchCondition;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

@ExtendWith(MockitoExtension.class)
class StoreRecommendationServiceTest {

    private static final Instant AS_OF = Instant.parse("2026-08-06T06:00:00Z");

    @Mock RecommendationHistoryLoader historyLoader;
    @Mock RecommendationSignalRepository signalRepository;

    @Test
    void combinesSearchAvailabilitySignalsAndHistoryIntoTheRealRanking() {
        InterpretedSearchCondition condition = condition();
        List<RecommendationSearchCandidate> candidates = List.of(
                new RecommendationSearchCandidate(
                        1L, 4, ReservationAvailability.NOT_REQUESTED, null),
                new RecommendationSearchCandidate(
                        2L, 4, ReservationAvailability.NOT_REQUESTED, null));
        given(historyLoader.load(41L, AS_OF)).willReturn(
                new RecommendationHistorySnapshot(List.of(
                        new RecommendationHistoryEvent(
                                11L,
                                2L,
                                AS_OF.minus(10, ChronoUnit.DAYS),
                                Set.of(202L)))));
        given(signalRepository.findSignals(List.of(1L, 2L), condition)).willReturn(Map.of(
                1L, RecommendationCandidateSignals.empty(),
                2L, new RecommendationCandidateSignals(
                        false, false, 0, 0, Set.of(202L))));

        List<RankedRecommendation> result = service().rank(
                41L, candidates, condition, AS_OF);

        assertThat(result).extracting(ranked -> ranked.candidate().storeId())
                .containsExactly(2L, 1L);
        assertThat(result.getFirst().historyScore()).isEqualTo(16);
        assertThat(result.getFirst().reason())
                .isEqualTo(RecommendationReason.KEYWORD);
    }

    @Test
    void missingStoreSignalExcludesOnlyThatCandidate() {
        InterpretedSearchCondition condition = condition();
        List<RecommendationSearchCandidate> candidates = List.of(
                new RecommendationSearchCandidate(
                        1L, 2, ReservationAvailability.AVAILABLE, null),
                new RecommendationSearchCandidate(
                        2L, 2, ReservationAvailability.AVAILABLE, null));
        given(historyLoader.load(null, AS_OF))
                .willReturn(RecommendationHistorySnapshot.empty());
        given(signalRepository.findSignals(List.of(1L, 2L), condition))
                .willReturn(Map.of(1L, RecommendationCandidateSignals.empty()));

        List<RankedRecommendation> result = service().rank(
                null, candidates, condition, AS_OF);

        assertThat(result).extracting(ranked -> ranked.candidate().storeId())
                .containsExactly(1L);
    }

    private StoreRecommendationService service() {
        return new StoreRecommendationService(
                historyLoader,
                signalRepository,
                new HistoryRecommendationRanker());
    }

    private static InterpretedSearchCondition condition() {
        return new InterpretedSearchCondition(
                List.of(), List.of(), List.of(), List.of(), null,
                null, null, null, "라멘");
    }
}
