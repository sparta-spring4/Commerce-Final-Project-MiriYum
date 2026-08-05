package com.miriyum.domain.store.search.interpreter;

import java.time.ZoneId;
import java.util.Objects;

/**
 * 검색 해석에 필요한 원문과 승인 사전 스냅샷이다.
 *
 * @param rawInput 사용자 검색 원문
 * @param vocabulary 승인 사전 스냅샷
 * @param zoneId 상대 날짜를 판정할 명시적 시간대
 */
public record InterpretationRequest(
        String rawInput,
        SearchVocabulary vocabulary,
        ZoneId zoneId) {

    public InterpretationRequest {
        Objects.requireNonNull(vocabulary, "vocabulary must not be null");
        Objects.requireNonNull(zoneId, "zoneId must not be null");
    }
}
