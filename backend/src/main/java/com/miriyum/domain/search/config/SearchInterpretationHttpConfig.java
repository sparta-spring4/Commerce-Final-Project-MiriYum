package com.miriyum.domain.search.config;

import java.net.http.HttpClient;
import java.time.Duration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 검색어 해석 설정을 애플리케이션 구성에 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiSearchInterpretationProperties.class)
public class SearchInterpretationHttpConfig {

    @Bean("openAiSearchInterpretationRestClient")
    public RestClient openAiSearchInterpretationRestClient(
            OpenAiSearchInterpretationProperties properties
    ) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(Duration.ofMillis(properties.connectTimeoutMs()))
                .version(HttpClient.Version.HTTP_1_1)
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
