package com.miriyum.domain.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** 검색어 해석용 OpenAI 호출과 MySQL 보완 후보의 제한값이다. */
@ConfigurationProperties("miriyum.store-search.llm")
public record OpenAiSearchInterpretationProperties(
        boolean enabled,
        String baseUrl,
        String apiKey,
        String model,
        long connectTimeoutMs,
        long responseTimeoutMs,
        int maxOutputTokens,
        int maxConcepts,
        int supplementCandidateLimit
) {

    public OpenAiSearchInterpretationProperties {
        if (baseUrl == null || baseUrl.isBlank()) {
            throw new IllegalArgumentException("baseUrl must not be blank");
        }
        if (model == null || model.isBlank()) {
            throw new IllegalArgumentException("model must not be blank");
        }
        if (enabled && (apiKey == null || apiKey.isBlank())) {
            throw new IllegalArgumentException("apiKey is required when enabled");
        }
        if (connectTimeoutMs < 1 || responseTimeoutMs < 1) {
            throw new IllegalArgumentException("timeouts must be positive");
        }
        if (maxOutputTokens < 1 || maxOutputTokens > 500) {
            throw new IllegalArgumentException("maxOutputTokens must be between 1 and 500");
        }
        if (maxConcepts < 1 || maxConcepts > 8) {
            throw new IllegalArgumentException("maxConcepts must be between 1 and 8");
        }
        if (supplementCandidateLimit < 1 || supplementCandidateLimit > 200) {
            throw new IllegalArgumentException(
                    "supplementCandidateLimit must be between 1 and 200");
        }
        apiKey = apiKey == null ? "" : apiKey;
    }
}
