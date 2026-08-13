package com.miriyum.domain.auth.cookie;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/** 카카오 OAuth state를 발급한 브라우저에만 묶기 위한 짧은 수명 HttpOnly 쿠키를 만든다. */
@Component
public class KakaoOAuthStateCookieFactory {

    private static final Duration STATE_MAX_AGE = Duration.ofMinutes(5);
    private static final String COOKIE_PATH = "/api/v1";

    public ResponseCookie activeCookie(TokenNamespace namespace, KakaoOAuthPurpose purpose, String state) {
        return ResponseCookie.from(cookieName(namespace, purpose), state)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(STATE_MAX_AGE)
                .build();
    }

    public ResponseCookie expiredCookie(TokenNamespace namespace, KakaoOAuthPurpose purpose) {
        return ResponseCookie.from(cookieName(namespace, purpose), "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(COOKIE_PATH)
                .maxAge(0)
                .build();
    }

    public boolean matchesRequestState(String cookieState, String requestState) {
        if (cookieState == null || requestState == null) {
            return false;
        }
        // state 일부가 일치할 때 비교 시간이 달라지지 않도록 바이트 단위로 비교한다.
        return MessageDigest.isEqual(
                cookieState.getBytes(StandardCharsets.UTF_8),
                requestState.getBytes(StandardCharsets.UTF_8));
    }

    public String cookieName(TokenNamespace namespace, KakaoOAuthPurpose purpose) {
        String namespaceLabel = namespace == TokenNamespace.CONSUMER ? "CONSUMER" : "STORE_OPERATOR";
        return "MIRIYUM_" + namespaceLabel + "_KAKAO_" + purpose.name() + "_STATE";
    }
}
