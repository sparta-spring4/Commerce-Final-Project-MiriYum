package com.miriyum.domain.auth.social.service;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 카카오 원문 회원번호를 저장하지 않고 조회 가능한 HMAC-SHA-256 fingerprint로 바꾼다. */
@Component
public class KakaoIdentityFingerprintGenerator {

    // SHA-SHA-256 : SHA-256에 서버만 아는 비밀키를 추가한 방식
    // 카카오 회원번호 + 서버 비밀키
    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final SecretKeySpec key;

    public KakaoIdentityFingerprintGenerator(
            @Value("${miriyum.kakao.identity-fingerprint-secret:${miriyum.jwt.secret}}") String secret
    ) {
        this.key = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM);
    }

    public String generate(String providerSubject) {
        if (providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("providerSubject must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(key);
            byte[] digest = mac.doFinal(providerSubject.getBytes(StandardCharsets.UTF_8));
            StringBuilder builder = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                builder.append(String.format("%02x", value));  //16진수로 변환
            }
            return builder.toString();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to fingerprint Kakao identity", exception);
        }
    }
}
