package com.miriyum.domain.auth.social.config;

import com.miriyum.domain.auth.social.service.KakaoOAuthStateService;
import com.miriyum.domain.auth.social.service.KakaoSignUpTicketService;
import java.time.Clock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** 카카오 OAuth state와 가입 티켓 검증기를 별도 비밀키로 구성한다. */
@Configuration
public class KakaoOAuthConfiguration {

    @Bean
    public KakaoOAuthStateService kakaoOAuthStateService(
            @Value("${miriyum.kakao.state-secret:${miriyum.jwt.secret}}") String stateSecret,
            @Value("${miriyum.jwt.issuer}") String issuer,
            Clock clock
    ) {
        return new KakaoOAuthStateService(stateSecret, issuer, clock);
    }

    @Bean
    public KakaoSignUpTicketService kakaoSignUpTicketService(
            @Value("${miriyum.kakao.sign-up-ticket-secret:${miriyum.jwt.secret}}") String ticketSecret,
            @Value("${miriyum.jwt.issuer}") String issuer,
            Clock clock
    ) {
        return new KakaoSignUpTicketService(ticketSecret, issuer, clock);
    }
}
