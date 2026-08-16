package com.miriyum.domain.search.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;
import org.springframework.scheduling.annotation.EnableAsync;

/** 의미 검색 외부 호출에 짧은 독립 timeout을 강제한다. */
@Configuration(proxyBeanMethods = false)
@EnableAsync
@EnableConfigurationProperties({
        OpenAiEmbeddingProperties.class,
        QdrantSemanticSearchProperties.class
})
public class SemanticSearchHttpConfig {

    @Bean("openAiEmbeddingRestClient")
    public RestClient openAiEmbeddingRestClient(OpenAiEmbeddingProperties properties) {
        return restClient(properties.baseUrl(), properties.connectTimeoutMs(),
                properties.responseTimeoutMs());
    }

    @Bean("qdrantSemanticRestClient")
    public RestClient qdrantSemanticRestClient(QdrantSemanticSearchProperties properties) {
        return restClient(properties.baseUrl(), properties.connectTimeoutMs(),
                properties.responseTimeoutMs());
    }

    private static RestClient restClient(String baseUrl, long connectMs, long responseMs) {
        if (baseUrl == null || baseUrl.isBlank() || connectMs < 1 || responseMs < 1) {
            throw new IllegalArgumentException("semantic HTTP settings are invalid");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(connectMs))
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(responseMs));
        return RestClient.builder().baseUrl(baseUrl).requestFactory(requestFactory).build();
    }
}
