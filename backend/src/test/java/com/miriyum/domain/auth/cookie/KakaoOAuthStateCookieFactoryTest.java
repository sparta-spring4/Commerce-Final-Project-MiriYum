package com.miriyum.domain.auth.cookie;

import static org.assertj.core.api.Assertions.assertThat;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.ResponseCookie;

class KakaoOAuthStateCookieFactoryTest {

    private final KakaoOAuthStateCookieFactory cookieFactory = new KakaoOAuthStateCookieFactory();

    @Test
    @DisplayName("일반 사용자 카카오 로그인 state는 5분 HttpOnly 쿠키로 발급한다")
    void createsConsumerLoginStateCookie() {
        // given
        String state = "signed-state";

        // when
        ResponseCookie cookie = cookieFactory.activeCookie(
                TokenNamespace.CONSUMER, KakaoOAuthPurpose.LOGIN, state);

        // then
        assertThat(cookie.getName()).isEqualTo("MIRIYUM_CONSUMER_KAKAO_LOGIN_STATE");
        assertThat(cookie.getValue()).isEqualTo(state);
        assertThat(cookie.getPath()).isEqualTo("/api/v1");
        assertThat(cookie.getMaxAge().getSeconds()).isEqualTo(300L);
        assertThat(cookie.isHttpOnly()).isTrue();
        assertThat(cookie.isSecure()).isTrue();
        assertThat(cookie.getSameSite()).isEqualTo("Lax");
    }

    @Test
    @DisplayName("카카오 연결 state 쿠키는 소비 뒤 즉시 만료한다")
    void expiresLinkStateCookie() {
        // when
        ResponseCookie cookie = cookieFactory.expiredCookie(
                TokenNamespace.STORE_OPERATOR, KakaoOAuthPurpose.LINK);

        // then
        assertThat(cookie.getName()).isEqualTo("MIRIYUM_STORE_OPERATOR_KAKAO_LINK_STATE");
        assertThat(cookie.getMaxAge().isZero()).isTrue();
    }

    @Test
    @DisplayName("콜백 state는 같은 브라우저에 발급한 쿠키 값과 같을 때만 통과한다")
    void matchesOnlyTheIssuedBrowserState() {
        assertThat(cookieFactory.matchesRequestState("issued-state", "issued-state")).isTrue();
        assertThat(cookieFactory.matchesRequestState("issued-state", "another-state")).isFalse();
        assertThat(cookieFactory.matchesRequestState(null, "issued-state")).isFalse();
    }
}
