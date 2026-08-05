package com.miriyum.domain.store.search.interpreter;

import java.util.List;
import java.util.Objects;

/**
 * 재현 가능한 규칙·사전 버전과 검색 해석 결과다.
 *
 * @param ruleVersion 적용한 해석 규칙 버전
 * @param vocabularyVersion 적용한 승인 사전 버전
 * @param condition 허용 조건과 남은 키워드
 * @param warnings 추측하지 않고 보존한 조건 경고
 */
public record InterpretationResult(
        String ruleVersion,
        String vocabularyVersion,
        InterpretedSearchCondition condition,
        List<InterpretationWarning> warnings) {

    public InterpretationResult {
        if (ruleVersion == null || ruleVersion.isBlank()) {
            throw new IllegalArgumentException("ruleVersion must not be blank");
        }
        if (vocabularyVersion == null || vocabularyVersion.isBlank()) {
            throw new IllegalArgumentException("vocabularyVersion must not be blank");
        }
        Objects.requireNonNull(condition, "condition must not be null");
        warnings = List.copyOf(warnings);
    }
}
