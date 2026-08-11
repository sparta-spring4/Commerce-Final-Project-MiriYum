package com.miriyum.domain.auth.cookie;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 더블 서브밋 쿠키 방식의 CSRF 토큰을 생성·비교한다. 서버에 별도 상태를 저장하지 않고
 * 쿠키 값과 {@code X-CSRF-TOKEN} 헤더 값이 같은지만 확인하는 무상태 검증이다.
 */
@Component
public class CsrfTokenGenerator {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final int TOKEN_BYTE_LENGTH = 32;

    public String generate() {
        byte[] bytes = new byte[TOKEN_BYTE_LENGTH];
        SECURE_RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    public boolean matches(String cookieValue, String headerValue) {
        if (cookieValue == null || cookieValue.isBlank() || headerValue == null || headerValue.isBlank()) {
            return false;
        }
        return MessageDigest.isEqual(
                cookieValue.getBytes(StandardCharsets.UTF_8),
                headerValue.getBytes(StandardCharsets.UTF_8)
        );
    }
}
