package com.miriyum.domain.recommendation.ranking;

import com.miriyum.domain.recommendation.repository.RecommendationSignalRepository;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.stereotype.Service;

@Service
public class StoreRecommendationService {

    public static final String RULE_VERSION = "history-v1";

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
            RecommendationSearchSignals condition,
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
                    candidate.reservationAvailability(),
                    candidate.distanceMeters(),
                    candidateSignals.currentMenuIds()));
        }
        return ranker.rank(rankingCandidates, history, asOf);
    }

}
