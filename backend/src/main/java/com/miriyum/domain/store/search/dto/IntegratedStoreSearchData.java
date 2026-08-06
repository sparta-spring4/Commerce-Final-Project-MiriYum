package com.miriyum.domain.store.search.dto;

import com.miriyum.domain.store.search.interpreter.InterpretationWarning;
import java.util.List;
import java.util.Objects;

/** 통합 검색의 결정적 해석 메타데이터와 cursor 결과다. */
public record IntegratedStoreSearchData(
        List<IntegratedStoreSearchItem> items,
        NormalizedSearchCondition normalizedCondition,
        List<InterpretationWarning> warnings,
        String ruleVersion,
        String vocabularyVersion,
        String nextCursor
) {

    public IntegratedStoreSearchData {
        items = List.copyOf(items);
        Objects.requireNonNull(normalizedCondition, "normalizedCondition is required");
        warnings = List.copyOf(warnings);
        if (ruleVersion == null || ruleVersion.isBlank()) {
            throw new IllegalArgumentException("ruleVersion is required");
        }
        if (vocabularyVersion == null || vocabularyVersion.isBlank()) {
            throw new IllegalArgumentException("vocabularyVersion is required");
        }
    }
}
