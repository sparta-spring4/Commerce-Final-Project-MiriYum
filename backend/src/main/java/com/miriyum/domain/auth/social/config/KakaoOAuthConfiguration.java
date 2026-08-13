package com.miriyum.domain.auth.social.config;

import com.miriyum.domain.auth.social.service.KakaoOAuthStateService;
import com.miriyum.domain.auth.social.service.KakaoSignUpTicketService;
import java.time.Clock;
import java.time.Duration;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

/** 카카오 OAuth state와 가입 티켓 검증기를 별도 비밀키로 구성한다. */
@Configuration
public class KakaoOAuthConfiguration {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(3);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(5);

    @Bean
    public RestClient kakaoRestClient() {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(CONNECT_TIMEOUT);
        requestFactory.setReadTimeout(READ_TIMEOUT);
        return RestClient.builder().requestFactory(requestFactory).build();
    }

    @Bean
    public KakaoOAuthStateService kakaoOAuthStateService(
            @Value("${miriyum.kakao.state-secret:}") String stateSecret,
            @Value("${miriyum.jwt.secret}") String jwtSecret,
            @Value("${miriyum.jwt.issuer}") String issuer,
            Clock clock
    ) {
        return new KakaoOAuthStateService(secretOrFallback(stateSecret, jwtSecret), issuer, clock);
    }

    @Bean
    public KakaoSignUpTicketService kakaoSignUpTicketService(
            @Value("${miriyum.kakao.sign-up-ticket-secret:}") String ticketSecret,
            @Value("${miriyum.jwt.secret}") String jwtSecret,
            @Value("${miriyum.jwt.issuer}") String issuer,
            Clock clock
    ) {
        return new KakaoSignUpTicketService(secretOrFallback(ticketSecret, jwtSecret), issuer, clock);
    }

    private String secretOrFallback(String configuredSecret, String jwtSecret) {
        return configuredSecret == null || configuredSecret.isBlank() ? jwtSecret : configuredSecret;
    }
}
