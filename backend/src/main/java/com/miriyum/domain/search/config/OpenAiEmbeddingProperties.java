package com.miriyum.domain.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** OpenAI 임베딩 호출의 Secret 및 제한 설정이다. */
@ConfigurationProperties("miriyum.store-search.semantic.openai")
public record OpenAiEmbeddingProperties(
        String baseUrl,
        String apiKey,
        String model,
        int dimensions,
        long connectTimeoutMs,
        long responseTimeoutMs
) {
}
