package com.miriyum.domain.recommendation.ranking;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Component;

@Component
public final class HistoryRecommendationRanker {

    private static final Duration THIRTY_DAYS = Duration.ofDays(30);
    private static final Duration NINETY_DAYS = Duration.ofDays(90);
    private static final Duration ONE_HUNDRED_EIGHTY_DAYS = Duration.ofDays(180);

    public List<RankedRecommendation> rank(
            List<RecommendationCandidate> candidates,
            RecommendationHistorySnapshot history,
            Instant asOf
    ) {
        Objects.requireNonNull(candidates, "candidates are required");
        Objects.requireNonNull(history, "history is required");
        Objects.requireNonNull(asOf, "asOf is required");
        if (candidates.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException("candidates must not contain null");
        }
        Set<Long> storeIds = new HashSet<>();
        if (candidates.stream().anyMatch(candidate -> !storeIds.add(candidate.storeId()))) {
            throw new IllegalArgumentException("candidate storeIds must be unique");
        }

        List<RankedRecommendation> ranked = new ArrayList<>(candidates.size());
        for (RecommendationCandidate candidate : candidates) {
            ranked.add(score(candidate, history, asOf));
        }
        ranked.sort((left, right) -> RecommendationCursorKey.compare(
                RecommendationCursorKey.from(left),
                RecommendationCursorKey.from(right)));
        return List.copyOf(ranked);
    }

    private static RankedRecommendation score(
            RecommendationCandidate candidate,
            RecommendationHistorySnapshot history,
            Instant asOf
    ) {
        int keyword = candidate.relevanceTier() * 8;
        int storeCategory = candidate.storeCategoryMatch() ? 12 : 0;
        int menuPrimaryCategory = candidate.menuPrimaryCategoryMatch() ? 10 : 0;
        int menuSecondaryCategory =
                Math.min(candidate.menuSecondaryCategoryMatchCount(), 2) * 4;
        int tag = Math.min(candidate.tagMatchCount(), 3) * 2;
        int availability = candidate.availability().score();
        int intentScore = keyword
                + storeCategory
                + menuPrimaryCategory
                + menuSecondaryCategory
                + tag
                + availability;

        int visitedStore = 0;
        int orderedMenu = 0;
        for (RecommendationHistoryEvent event : history.events()) {
            if (event.storeId() == candidate.storeId()) {
                visitedStore = Math.min(
                        18,
                        visitedStore + recencyScore(event.occurredAt(), asOf, 10, 6, 3));
            }
            if (intersects(candidate.currentMenuIds(), event.menuIds())) {
                orderedMenu = Math.min(
                        12,
                        orderedMenu + recencyScore(event.occurredAt(), asOf, 6, 4, 2));
            }
        }
        int historyScore = visitedStore + orderedMenu;
        RecommendationReason reason = highestReason(
                keyword,
                storeCategory,
                menuPrimaryCategory,
                menuSecondaryCategory,
                tag,
                availability,
                visitedStore,
                orderedMenu);
        return new RankedRecommendation(
                candidate,
                intentScore,
                historyScore,
                intentScore + historyScore,
                reason);
    }

    private static int recencyScore(
            Instant occurredAt,
            Instant asOf,
            int withinThirty,
            int withinNinety,
            int withinOneHundredEighty
    ) {
        Duration age = Duration.between(occurredAt, asOf);
        if (age.isNegative()) {
            return 0;
        }
        if (age.compareTo(THIRTY_DAYS) <= 0) {
            return withinThirty;
        }
        if (age.compareTo(NINETY_DAYS) <= 0) {
            return withinNinety;
        }
        if (age.compareTo(ONE_HUNDRED_EIGHTY_DAYS) <= 0) {
            return withinOneHundredEighty;
        }
        return 0;
    }

    private static boolean intersects(Set<Long> first, Set<Long> second) {
        if (first.isEmpty() || second.isEmpty()) {
            return false;
        }
        Set<Long> smaller = first.size() <= second.size() ? first : second;
        Set<Long> larger = smaller == first ? second : first;
        return smaller.stream().anyMatch(larger::contains);
    }

    private static RecommendationReason highestReason(
            int keyword,
            int storeCategory,
            int menuPrimaryCategory,
            int menuSecondaryCategory,
            int tag,
            int availability,
            int visitedStore,
            int orderedMenu
    ) {
        List<Contribution> contributions = List.of(
                new Contribution(keyword, 0, RecommendationReason.KEYWORD),
                new Contribution(storeCategory, 1, RecommendationReason.STORE_CATEGORY),
                new Contribution(
                        menuPrimaryCategory,
                        2,
                        RecommendationReason.MENU_PRIMARY_CATEGORY),
                new Contribution(
                        menuSecondaryCategory,
                        3,
                        RecommendationReason.MENU_SECONDARY_CATEGORY),
                new Contribution(tag, 4, RecommendationReason.TAG),
                new Contribution(availability, 5, RecommendationReason.AVAILABILITY),
                new Contribution(visitedStore, 6, RecommendationReason.VISITED_STORE),
                new Contribution(orderedMenu, 7, RecommendationReason.ORDERED_MENU));
        return contributions.stream()
                .filter(contribution -> contribution.score() > 0)
                .sorted((left, right) -> {
                    int score = Integer.compare(right.score(), left.score());
                    return score != 0
                            ? score
                            : Integer.compare(left.priority(), right.priority());
                })
                .map(Contribution::reason)
                .findFirst()
                .orElse(null);
    }

    private record Contribution(
            int score,
            int priority,
            RecommendationReason reason
    ) {
    }
}
