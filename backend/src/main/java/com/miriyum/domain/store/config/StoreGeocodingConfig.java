package com.miriyum.domain.store.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(StoreGeocodingProperties.class)
public class StoreGeocodingConfig {

    @Bean("storeGeocodingRestClient")
    public RestClient storeGeocodingRestClient(StoreGeocodingProperties properties) {
        if (properties.connectTimeoutMs() <= 0) {
            throw new IllegalArgumentException(
                    "geocoding connect timeout must be positive");
        }
        if (properties.responseTimeoutMs() <= 0) {
            throw new IllegalArgumentException(
                    "geocoding response timeout must be positive");
        }
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .build();
        JdkClientHttpRequestFactory requestFactory =
                new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(Duration.ofMillis(properties.responseTimeoutMs()));
        return RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
    }
}
