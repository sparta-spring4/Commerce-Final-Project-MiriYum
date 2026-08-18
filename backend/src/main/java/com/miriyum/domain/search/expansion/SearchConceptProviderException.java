package com.miriyum.domain.search.expansion;

import java.util.Objects;

/** 외부 응답 세부 내용을 노출하지 않는 저카디널리티 실패다. */
public final class SearchConceptProviderException extends RuntimeException {

    private final SearchConceptFailureReason reason;
    private final long inputTokens;
    private final long outputTokens;

    public SearchConceptProviderException(SearchConceptFailureReason reason) {
        super("search concept provider unavailable");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.inputTokens = 0;
        this.outputTokens = 0;
    }

    SearchConceptProviderException(
            SearchConceptFailureReason reason,
            long inputTokens,
            long outputTokens
    ) {
        super("search concept provider unavailable");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        if (inputTokens < 0 || outputTokens < 0) {
            throw new IllegalArgumentException("token usage must be non-negative");
        }
        this.inputTokens = inputTokens;
        this.outputTokens = outputTokens;
    }

    SearchConceptProviderException(
            SearchConceptFailureReason reason,
            RuntimeException cause
    ) {
        super("search concept provider unavailable", cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
        this.inputTokens = 0;
        this.outputTokens = 0;
    }

    public SearchConceptFailureReason reason() {
        return reason;
    }

    public long inputTokens() {
        return inputTokens;
    }

    public long outputTokens() {
        return outputTokens;
    }
}
