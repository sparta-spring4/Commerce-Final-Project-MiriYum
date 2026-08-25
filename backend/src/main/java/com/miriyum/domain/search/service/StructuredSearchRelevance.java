package com.miriyum.domain.search.service;

import com.miriyum.domain.search.query.IntegratedStoreSearchQuery;

/** 음식 근거를 먼저, 기존 관련도를 그 안에서만 비교하는 정적 그룹 키다. */
public record StructuredSearchRelevance(
        int structuredRelevance,
        int relevanceTier
) implements Comparable<StructuredSearchRelevance> {

    public static final int MAX_STRUCTURED_RELEVANCE =
            IntegratedStoreSearchQuery.MAX_STRUCTURED_RELEVANCE;
    public static final int MAX_LEGACY_RELEVANCE_TIER = 4;

    public StructuredSearchRelevance {
        if (structuredRelevance < 0
                || structuredRelevance > MAX_STRUCTURED_RELEVANCE
                || relevanceTier < 0
                || relevanceTier > MAX_LEGACY_RELEVANCE_TIER) {
            throw new IllegalArgumentException("invalid structured search relevance");
        }
    }

    public static StructuredSearchRelevance of(
            int structuredRelevance,
            int relevanceTier
    ) {
        return new StructuredSearchRelevance(structuredRelevance, relevanceTier);
    }

    public boolean sameGroup(StructuredSearchRelevance other) {
        return other != null
                && structuredRelevance == other.structuredRelevance
                && relevanceTier == other.relevanceTier;
    }

    @Override
    public int compareTo(StructuredSearchRelevance other) {
        int compared = Integer.compare(other.structuredRelevance, structuredRelevance);
        return compared != 0
                ? compared
                : Integer.compare(other.relevanceTier, relevanceTier);
    }
}
