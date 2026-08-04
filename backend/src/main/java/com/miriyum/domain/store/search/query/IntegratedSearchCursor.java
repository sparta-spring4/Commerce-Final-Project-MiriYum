package com.miriyum.domain.store.search.query;

/** 안정 정렬의 마지막 값과 매장 ID 동률 해소자다. */
public record IntegratedSearchCursor(String sortValue, long storeId) {

    public IntegratedSearchCursor {
        if (sortValue == null || storeId <= 0) {
            throw new IllegalArgumentException("cursor values are required");
        }
    }
}
