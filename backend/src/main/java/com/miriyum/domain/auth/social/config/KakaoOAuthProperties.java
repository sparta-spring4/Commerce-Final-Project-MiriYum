package com.miriyum.domain.auth.social.config;

import java.util.Arrays;
import java.util.List;

import lombok.Getter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 카카오 OAuth 서버 연동에 필요한 환경별 설정값을 읽는다. */
@Component
public class KakaoOAuthProperties {

    @Getter
    private final boolean enabled;
    private final String restApiKey;
    private final String clientSecret;
    private final List<String> redirectUris;

    public KakaoOAuthProperties(
            @Value("${miriyum.kakao.enabled:false}") boolean enabled,
            @Value("${miriyum.kakao.rest-api-key:}") String restApiKey,
            @Value("${miriyum.kakao.client-secret:}") String clientSecret,
            @Value("${miriyum.kakao.redirect-uris:}") String redirectUris
    ) {
        this.enabled = enabled;
        this.restApiKey = restApiKey;
        this.clientSecret = clientSecret;
        this.redirectUris = Arrays.stream(redirectUris.split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .toList();
    }

    public String restApiKey() {
        return restApiKey;
    }

    public String clientSecret() {
        return clientSecret;
    }

    public boolean isAllowedRedirectUri(String redirectUri) {
        return redirectUris.contains(redirectUri);
    }
}
