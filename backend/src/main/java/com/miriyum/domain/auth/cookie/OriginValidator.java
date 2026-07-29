package com.miriyum.domain.auth.cookie;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 재발급 요청의 Origin(없으면 Referer)이 허용된 프론트엔드 Origin과 같은지 확인한다.
 */
@Component
public class OriginValidator {

    private final String allowedOrigin;

    public OriginValidator(@Value("${miriyum.security.allowed-origin}") String allowedOrigin) {
        this.allowedOrigin = allowedOrigin;
    }

    public boolean isSameOrigin(String originHeader, String refererHeader) {
        if (originHeader != null && !originHeader.isBlank()) {
            return allowedOrigin.equals(originHeader);
        }
        return refererHeader != null && refererHeader.startsWith(allowedOrigin);
    }
}
