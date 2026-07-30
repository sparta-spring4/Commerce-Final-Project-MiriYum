package com.miriyum.domain.auth.cookie;

import com.miriyum.domain.auth.jwt.TokenNamespace;
import java.time.Duration;
import org.springframework.http.ResponseCookie;
import org.springframework.stereotype.Component;

/**
 * namespace별 Refresh·CSRF 쿠키를 스펙에 맞는 이름·Path·속성으로 생성한다.
 */
@Component
public class AuthCookieFactory {

    private static final Duration REFRESH_TOKEN_MAX_AGE = Duration.ofDays(14);

    public ResponseCookie refreshCookie(TokenNamespace namespace, String refreshToken) {
        return ResponseCookie.from(namespace.refreshCookieName(), refreshToken)
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(namespace.cookiePath())
                .maxAge(REFRESH_TOKEN_MAX_AGE)
                .build();
    }

    public ResponseCookie expiredRefreshCookie(TokenNamespace namespace) {
        return ResponseCookie.from(namespace.refreshCookieName(), "")
                .httpOnly(true)
                .secure(true)
                .sameSite("Lax")
                .path(namespace.cookiePath())
                .maxAge(0)
                .build();
    }

    public ResponseCookie csrfCookie(TokenNamespace namespace, String csrfToken) {
        return ResponseCookie.from(namespace.csrfCookieName(), csrfToken)
                .httpOnly(false)
                .secure(true)
                .sameSite("Lax")
                .path(namespace.cookiePath())
                .build();
    }
}
