package com.miriyum.domain.search.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Qdrant 재구축 가능 인덱스의 연결 및 검색 설정이다. */
@ConfigurationProperties("miriyum.store-search.semantic.qdrant")
public record QdrantSemanticSearchProperties(
        String baseUrl,
        String apiKey,
        String collection,
        double scoreThreshold,
        long connectTimeoutMs,
        long responseTimeoutMs
) {
}
