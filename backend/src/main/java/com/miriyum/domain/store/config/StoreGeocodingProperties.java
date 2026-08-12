package com.miriyum.domain.store.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * 매장 주소 지오코딩 HTTP 경계의 환경 주입 설정이다.
 */
@ConfigurationProperties("miriyum.store.geocoding")
public record StoreGeocodingProperties(
        String baseUrl,
        String restApiKey,
        long connectTimeoutMs,
        long responseTimeoutMs
) {
}
