package com.miriyum.domain.store.recommendation.ranking;

import com.miriyum.domain.store.search.dto.ReservationAvailability;
import com.miriyum.domain.store.search.interpreter.InterpretedSearchCondition;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class StoreRecommendationService {

    private final RecommendationHistoryLoader historyLoader;
    private final RecommendationSignalRepository signalRepository;
    private final HistoryRecommendationRanker ranker;

    public StoreRecommendationService(
            RecommendationHistoryLoader historyLoader,
            RecommendationSignalRepository signalRepository,
            HistoryRecommendationRanker ranker
    ) {
        this.historyLoader = historyLoader;
        this.signalRepository = signalRepository;
        this.ranker = ranker;
    }

    public List<RankedRecommendation> rank(
            Long consumerAccountId,
            List<RecommendationSearchCandidate> candidates,
            InterpretedSearchCondition condition,
            Instant asOf
    ) {
        Objects.requireNonNull(candidates, "candidates are required");
        Objects.requireNonNull(condition, "condition is required");
        Objects.requireNonNull(asOf, "asOf is required");
        if (candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("candidates must not contain null");
        }
        List<Long> storeIds = candidates.stream()
                .map(RecommendationSearchCandidate::storeId)
                .toList();
        Map<Long, RecommendationCandidateSignals> signals =
                signalRepository.findSignals(storeIds, condition);
        RecommendationHistorySnapshot history = historyLoader.load(consumerAccountId, asOf);

        List<RecommendationCandidate> rankingCandidates = new ArrayList<>(candidates.size());
        for (RecommendationSearchCandidate candidate : candidates) {
            RecommendationCandidateSignals candidateSignals = signals.get(candidate.storeId());
            if (candidateSignals == null) {
                continue;
            }
            rankingCandidates.add(new RecommendationCandidate(
                    candidate.storeId(),
                    candidate.relevanceTier(),
                    candidateSignals.storeCategoryMatch(),
                    candidateSignals.menuPrimaryCategoryMatch(),
                    candidateSignals.menuSecondaryCategoryMatchCount(),
                    candidateSignals.tagMatchCount(),
                    toRecommendationAvailability(candidate.reservationAvailability()),
                    candidate.distanceMeters(),
                    candidateSignals.currentMenuIds()));
        }
        return ranker.rank(rankingCandidates, history, asOf);
    }

    private static RecommendationAvailability toRecommendationAvailability(
            ReservationAvailability availability
    ) {
        return switch (availability) {
            case AVAILABLE -> RecommendationAvailability.AVAILABLE;
            case NOT_REQUESTED -> RecommendationAvailability.NOT_REQUESTED;
            case UNAVAILABLE -> RecommendationAvailability.UNAVAILABLE;
        };
    }
}
