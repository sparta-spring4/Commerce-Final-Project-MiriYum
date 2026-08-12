package com.miriyum.domain.search.repository;

import java.util.List;

/** QueryDSL seek 조회 한 페이지와 다음 opaque cursor다. */
public record IntegratedStoreSearchSlice(
        List<IntegratedStoreSearchCandidate> content,
        String nextCursor
) {
    public IntegratedStoreSearchSlice {
        content = List.copyOf(content);
    }
}
