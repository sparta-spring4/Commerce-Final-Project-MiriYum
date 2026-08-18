package com.miriyum.domain.search.expansion;

public enum SearchConceptFailureReason {
    REFUSAL,
    TIMEOUT,
    MALFORMED_RESPONSE,
    HTTP_ERROR,
    PROVIDER_ERROR,
    SENSITIVE_INPUT;

    public String metricValue() {
        return name().toLowerCase(java.util.Locale.ROOT);
    }
}
