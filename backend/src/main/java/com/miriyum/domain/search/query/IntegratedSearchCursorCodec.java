package com.miriyum.domain.search.query;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.Base64;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** 검색 fingerprint와 정렬을 결합한 versioned opaque cursor codec이다. */
@Component
public final class IntegratedSearchCursorCodec {

    private static final String VERSION = "v1";
    private static final int MAX_CURSOR_LENGTH = 1_024;
    private static final String HMAC_ALGORITHM = "HmacSHA256";
    private static final String SIGNING_CONTEXT = "miriyum-store-search-cursor\0";
    private static final String PRINCIPAL_CONTEXT = "miriyum-store-search-principal\0";
    private final byte[] signingKey;

    public IntegratedSearchCursorCodec(
            @Value("${miriyum.jwt.secret}") String secret
    ) {
        if (secret == null || secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            throw new IllegalArgumentException("cursor signing secret must be at least 32 bytes");
        }
        this.signingKey = secret.getBytes(StandardCharsets.UTF_8).clone();
    }

    public String encode(
            IntegratedStoreSearchQuery query,
            String sortValue,
            long storeId
    ) {
        return encode(query, 0, sortValue, storeId);
    }

    /** Returns a keyed opaque scope that binds cursors to an anonymous or consumer principal. */
    public String principalScope(Long consumerAccountId) {
        if (consumerAccountId != null && consumerAccountId <= 0) {
            throw new IllegalArgumentException("consumerAccountId must be positive");
        }
        String principal = consumerAccountId == null
                ? "anonymous"
                : "consumer:" + consumerAccountId;
        return sign(PRINCIPAL_CONTEXT, principal);
    }

    public String encode(
            IntegratedStoreSearchQuery query,
            int relevanceTier,
            String sortValue,
            long storeId
    ) {
        if (query == null || sortValue == null || storeId <= 0) {
            throw new IllegalArgumentException("query and cursor values are required");
        }
        String encodedSortValue = Base64.getUrlEncoder().withoutPadding().encodeToString(
                sortValue.getBytes(StandardCharsets.UTF_8));
        String payload = String.join(
                ".",
                VERSION,
                query.fingerprint(),
                query.sort().name(),
                Integer.toString(relevanceTier),
                encodedSortValue,
                Long.toString(storeId));
        return payload + "." + sign(payload);
    }

    IntegratedSearchCursor decode(
            String rawCursor,
            String expectedFingerprint,
            IntegratedStoreSearchSort expectedSort
    ) {
        try {
            if (rawCursor == null
                    || rawCursor.isBlank()
                    || rawCursor.length() > MAX_CURSOR_LENGTH) {
                throw validationFailed();
            }
            String[] parts = rawCursor.split("\\.", -1);
            if (parts.length != 7
                    || !VERSION.equals(parts[0])
                    || !expectedFingerprint.equals(parts[1])
                    || !expectedSort.name().equals(parts[2])) {
                throw validationFailed();
            }
            String payload = String.join(".", Arrays.copyOf(parts, 6));
            byte[] expectedSignature = Base64.getUrlDecoder().decode(sign(payload));
            byte[] actualSignature = Base64.getUrlDecoder().decode(parts[6]);
            if (!MessageDigest.isEqual(expectedSignature, actualSignature)) {
                throw validationFailed();
            }
            int relevanceTier = Integer.parseInt(parts[3]);
            String sortValue = new String(
                    Base64.getUrlDecoder().decode(parts[4]), StandardCharsets.UTF_8);
            long storeId = Long.parseLong(parts[5]);
            if (sortValue.isEmpty() || storeId <= 0) {
                throw validationFailed();
            }
            return new IntegratedSearchCursor(relevanceTier, sortValue, storeId);
        } catch (IllegalArgumentException exception) {
            throw validationFailed();
        }
    }

    private static ServiceException validationFailed() {
        return new ServiceException(CommonErrorCode.VALIDATION_FAILED);
    }

    private String sign(String payload) {
        return sign(SIGNING_CONTEXT, payload);
    }

    private String sign(String context, String payload) {
        try {
            Mac mac = Mac.getInstance(HMAC_ALGORITHM);
            mac.init(new SecretKeySpec(signingKey, HMAC_ALGORITHM));
            byte[] signature = mac.doFinal(
                    (context + payload).getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(signature);
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA256 is unavailable", exception);
        }
    }
}
