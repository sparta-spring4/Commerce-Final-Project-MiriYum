package com.miriyum.domain.auth.refreshtoken;

import java.security.SecureRandom;
import java.util.Base64;
import org.springframework.stereotype.Component;

/**
 * 계정 정보가 드러나지 않는 Refresh Token family·token 식별자를 만든다.
 */
@Component
public class RefreshTokenIdentityGenerator {

    private static final int ID_BYTES = 32;

    private final SecureRandom secureRandom = new SecureRandom();

    public RefreshTokenIdentity generate() {
        return new RefreshTokenIdentity(generateId(), generateId());
    }

    private String generateId() {
        byte[] bytes = new byte[ID_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
