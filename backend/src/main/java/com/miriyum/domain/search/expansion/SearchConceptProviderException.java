package com.miriyum.domain.search.expansion;

import java.util.Objects;

/** 외부 응답 세부 내용을 노출하지 않는 저카디널리티 실패다. */
public final class SearchConceptProviderException extends RuntimeException {

    private final SearchConceptFailureReason reason;

    public SearchConceptProviderException(SearchConceptFailureReason reason) {
        super("search concept provider unavailable");
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    SearchConceptProviderException(
            SearchConceptFailureReason reason,
            RuntimeException cause
    ) {
        super("search concept provider unavailable", cause);
        this.reason = Objects.requireNonNull(reason, "reason must not be null");
    }

    public SearchConceptFailureReason reason() {
        return reason;
    }
}
