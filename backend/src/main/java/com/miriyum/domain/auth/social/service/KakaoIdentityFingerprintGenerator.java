package com.miriyum.domain.auth.social.service;

import com.miriyum.domain.auth.social.dto.KakaoIdentityFingerprint;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 카카오 원문 회원번호를 저장하지 않고, 키 버전별 HMAC fingerprint로 바꾼다. */
@Component
public class KakaoIdentityFingerprintGenerator {

    private static final String HMAC_ALGORITHM = "HmacSHA256";

    private final FingerprintKey activeKey;
    private final FingerprintKey previousKey;

    public KakaoIdentityFingerprintGenerator(
            @Value("${miriyum.kakao.identity-fingerprint-active-key-version:}") String activeKeyVersion,
            @Value("${miriyum.kakao.identity-fingerprint-active-secret:}") String activeSecret,
            @Value("${miriyum.kakao.identity-fingerprint-previous-key-version:}") String previousKeyVersion,
            @Value("${miriyum.kakao.identity-fingerprint-previous-secret:}") String previousSecret
    ) {
        this.activeKey = FingerprintKey.of(activeKeyVersion, activeSecret);
        this.previousKey = FingerprintKey.of(previousKeyVersion, previousSecret);
    }

    public KakaoIdentityFingerprint generateActive(String providerSubject) {
        return generate(activeKey.requireConfigured(), providerSubject);
    }

    public Optional<KakaoIdentityFingerprint> generatePrevious(String providerSubject) {
        return previousKey.optional().map(key -> generate(key, providerSubject));
    }

    public boolean isAllowedKeyVersion(String keyVersion) {
        if (keyVersion == null || keyVersion.isBlank()) {
            return false;
        }
        if (activeKey.requireConfigured().version().equals(keyVersion)) {
            return true;
        }
        return previousKey.optional().map(FingerprintKey::version).filter(keyVersion::equals).isPresent();
    }

    private KakaoIdentityFingerprint generate(FingerprintKey key, String providerSubject) {
        if (providerSubject == null || providerSubject.isBlank()) {
            throw new IllegalArgumentException("providerSubject must not be blank");
        }
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(key.secret().getBytes(StandardCharsets.UTF_8), HMAC_ALGORITHM));
            byte[] digest = mac.doFinal(providerSubject.getBytes(StandardCharsets.UTF_8));
            StringBuilder fingerprint = new StringBuilder(digest.length * 2);
            for (byte value : digest) {
                fingerprint.append(String.format("%02x", value));
            }
            return new KakaoIdentityFingerprint(key.version(), fingerprint.toString());
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("Unable to fingerprint Kakao identity", exception);
        }
    }

    private record FingerprintKey(String version, String secret) {

        private static FingerprintKey of(String version, String secret) {
            return new FingerprintKey(version == null ? "" : version, secret == null ? "" : secret);
        }

        private FingerprintKey requireConfigured() {
            if (version.isBlank() || secret.isBlank()) {
                throw new ServiceException(CommonErrorCode.SERVICE_UNAVAILABLE);
            }
            return this;
        }

        private Optional<FingerprintKey> optional() {
            if (version.isBlank() && secret.isBlank()) {
                return Optional.empty();
            }
            return Optional.of(requireConfigured());
        }
    }
}
