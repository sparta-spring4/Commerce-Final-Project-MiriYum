package com.miriyum.domain.search.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/** 검색어 해석 설정을 애플리케이션 구성에 등록한다. */
@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(OpenAiSearchInterpretationProperties.class)
public class SearchInterpretationHttpConfig {
}
