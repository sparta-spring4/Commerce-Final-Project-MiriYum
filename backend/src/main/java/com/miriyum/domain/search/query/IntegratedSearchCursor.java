package com.miriyum.domain.search.query;

/** 안정 정렬의 구조화·기존 관련도, 마지막 값과 매장 ID 동률 해소자다. */
public record IntegratedSearchCursor(
        int structuredRelevance,
        int relevanceTier,
        String sortValue,
        long storeId
) {

    public IntegratedSearchCursor {
        if (structuredRelevance < 0
                || structuredRelevance
                > IntegratedStoreSearchQuery.MAX_STRUCTURED_RELEVANCE
                || relevanceTier < 0
                || relevanceTier > 4
                || sortValue == null
                || storeId <= 0) {
            throw new IllegalArgumentException("cursor values are required");
        }
    }
}
