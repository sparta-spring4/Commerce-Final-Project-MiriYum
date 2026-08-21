package com.miriyum.domain.store.evidence;

import com.miriyum.domain.store.error.StoreErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Map;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
public class BusinessRegistrationEvidenceUploadValidator {

    static final long MAX_SIZE_BYTES = 10_485_760L;
    private static final byte[] PDF = "%PDF-".getBytes(StandardCharsets.US_ASCII);
    private static final byte[] JPEG = {(byte) 0xff, (byte) 0xd8, (byte) 0xff};
    private static final byte[] PNG = {
            (byte) 0x89, 0x50, 0x4e, 0x47, 0x0d, 0x0a, 0x1a, 0x0a
    };
    private static final Map<String, byte[]> SIGNATURES = Map.of(
            "application/pdf", PDF,
            "image/jpeg", JPEG,
            "image/png", PNG);

    public ValidatedBusinessRegistrationEvidence validate(MultipartFile upload) {
        if (upload == null || upload.isEmpty()) {
            throw new ServiceException(StoreErrorCode.ONBOARDING_EVIDENCE_INVALID);
        }
        if (upload.getSize() > MAX_SIZE_BYTES) {
            throw new ServiceException(StoreErrorCode.ONBOARDING_EVIDENCE_SIZE_EXCEEDED);
        }
        String contentType = upload.getContentType();
        byte[] expectedSignature = SIGNATURES.get(contentType);
        if (expectedSignature == null) {
            throw new ServiceException(StoreErrorCode.ONBOARDING_EVIDENCE_INVALID);
        }

        byte[] bytes = read(upload);
        if (bytes.length > MAX_SIZE_BYTES || !startsWith(bytes, expectedSignature)) {
            throw new ServiceException(bytes.length > MAX_SIZE_BYTES
                    ? StoreErrorCode.ONBOARDING_EVIDENCE_SIZE_EXCEEDED
                    : StoreErrorCode.ONBOARDING_EVIDENCE_INVALID);
        }
        if ("application/pdf".equals(contentType) && isEncryptedPdf(bytes)) {
            throw new ServiceException(StoreErrorCode.ONBOARDING_EVIDENCE_INVALID);
        }
        return new ValidatedBusinessRegistrationEvidence(contentType, bytes, sha256(bytes));
    }

    static String sha256(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is unavailable", exception);
        }
    }

    private static byte[] read(MultipartFile upload) {
        try {
            return upload.getBytes();
        } catch (IOException exception) {
            throw new ServiceException(StoreErrorCode.ONBOARDING_EVIDENCE_INVALID);
        }
    }

    private static boolean startsWith(byte[] bytes, byte[] signature) {
        if (bytes.length < signature.length) return false;
        for (int index = 0; index < signature.length; index++) {
            if (bytes[index] != signature[index]) return false;
        }
        return true;
    }

    private static boolean isEncryptedPdf(byte[] bytes) {
        return new String(bytes, StandardCharsets.ISO_8859_1).contains("/Encrypt");
    }
}
