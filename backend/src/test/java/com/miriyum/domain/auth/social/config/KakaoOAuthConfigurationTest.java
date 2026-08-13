package com.miriyum.domain.auth.social.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.social.service.KakaoOAuthStateService;
import com.miriyum.domain.auth.social.service.KakaoSignUpTicketService;
import java.time.Clock;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

class KakaoOAuthConfigurationTest {

    private static final String JWT_SECRET = "test-only-jwt-secret-must-be-at-least-32-bytes";

    @Test
    @DisplayName("빈 카카오 state와 가입 티켓 비밀값은 JWT 비밀값으로 대체해 서버를 시작한다")
    void fallsBackToJwtSecretWhenKakaoSecretsAreBlank() {
        new ApplicationContextRunner()
                .withUserConfiguration(KakaoOAuthConfiguration.class)
                .withBean(Clock.class, Clock::systemUTC)
                .withPropertyValues(
                        "miriyum.jwt.secret=" + JWT_SECRET,
                        "miriyum.jwt.issuer=miriyum",
                        "miriyum.kakao.state-secret=",
                        "miriyum.kakao.sign-up-ticket-secret=")
                .run(context -> {
                    assertThat(context).hasNotFailed();
                    assertThat(context).hasSingleBean(KakaoOAuthStateService.class);
                    assertThat(context).hasSingleBean(KakaoSignUpTicketService.class);
                });
    }
}
