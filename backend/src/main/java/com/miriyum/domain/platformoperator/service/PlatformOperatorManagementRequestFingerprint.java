package com.miriyum.domain.platformoperator.service;

import com.miriyum.global.idempotency.RequestFingerprint;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "miriyum.platform-operator", name = "enabled", havingValue = "true")
public class PlatformOperatorManagementRequestFingerprint {

    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private final byte[] secret;

    public PlatformOperatorManagementRequestFingerprint(
            @Value("${miriyum.platform-operator.reauthentication-fingerprint-secret}") String secret
    ) {
        if (secret == null || secret.length() < 32) {
            throw new IllegalArgumentException("platform operator fingerprint secret must contain at least 32 characters");
        }
        this.secret = secret.getBytes(StandardCharsets.UTF_8);
    }

    public String accountCreation(String canonicalInput, String temporaryPassword) {
        return RequestFingerprint.of(canonicalInput
                + "\ntemporaryPasswordDigest=" + hmac("account-temporary-password\0" + temporaryPassword));
    }

    private String hmac(String value) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(secret, HMAC_ALGORITHM));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is unavailable", impossible);
        }
    }
}
