package com.miriyum.domain.platformoperator.service.membersupport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "miriyum.member-support", name = "enabled", havingValue = "true")
public final class MemberSupportCrypto {
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private final byte[] digestKey;
    private final int activeKeyVersion;
    private final byte[] activeEncryptionKey;
    private final Map<Integer, byte[]> decryptionKeys;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public MemberSupportCrypto(MemberSupportProperties properties) {
        this(properties.proofDigestSecret(),
                properties.piiEncryptionActiveKeyVersion(), properties.piiEncryptionActiveKey(),
                properties.piiEncryptionPreviousKeyVersion(), properties.piiEncryptionPreviousKey());
    }

    public MemberSupportCrypto(String proofDigestSecret, String base64EncryptionKey) {
        this(proofDigestSecret, 1, base64EncryptionKey, 0, "");
    }

    public MemberSupportCrypto(String proofDigestSecret,
                               int activeKeyVersion,
                               String base64ActiveEncryptionKey,
                               int previousKeyVersion,
                               String base64PreviousEncryptionKey) {
        this.digestKey = proofDigestSecret.getBytes(StandardCharsets.UTF_8);
        this.activeKeyVersion = activeKeyVersion;
        this.activeEncryptionKey = decodeKey(base64ActiveEncryptionKey);
        Map<Integer, byte[]> keys = new HashMap<>();
        keys.put(activeKeyVersion, activeEncryptionKey);
        if (previousKeyVersion != 0) {
            keys.put(previousKeyVersion, decodeKey(base64PreviousEncryptionKey));
        }
        this.decryptionKeys = Map.copyOf(keys);
        if (digestKey.length == 0 || activeKeyVersion < 1 || activeKeyVersion > 255
                || (previousKeyVersion != 0 && (previousKeyVersion < 1 || previousKeyVersion > 255
                || previousKeyVersion == activeKeyVersion))) {
            throw new IllegalArgumentException("member-support cryptographic keys are invalid");
        }
    }

    public String digest(String value) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(digestKey, "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(value.getBytes(StandardCharsets.UTF_8)));
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("HMAC-SHA-256 unavailable", exception);
        }
    }

    public byte[] encrypt(String plaintext) {
        byte[] nonce = new byte[NONCE_BYTES];
        random.nextBytes(nonce);
        byte version = (byte) activeKeyVersion;
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(activeEncryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(new byte[]{version});
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(1 + nonce.length + ciphertext.length)
                    .put(version).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM unavailable", exception);
        }
    }

    public String decrypt(byte[] payload) {
        if (payload == null || payload.length <= 1 + NONCE_BYTES) {
            throw new IllegalArgumentException("invalid encrypted payload");
        }
        byte version = payload[0];
        byte[] encryptionKey = decryptionKeys.get(Byte.toUnsignedInt(version));
        if (encryptionKey == null) {
            throw new IllegalArgumentException("invalid encrypted payload");
        }
        byte[] nonce = Arrays.copyOfRange(payload, 1, 1 + NONCE_BYTES);
        byte[] ciphertext = Arrays.copyOfRange(payload, 1 + NONCE_BYTES, payload.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            cipher.updateAAD(new byte[]{version});
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("invalid encrypted payload", exception);
        }
    }

    private static byte[] decodeKey(String base64Key) {
        try {
            byte[] decoded = Base64.getDecoder().decode(base64Key);
            if (decoded.length != 32) {
                throw new IllegalArgumentException("member-support cryptographic keys are invalid");
            }
            return decoded;
        } catch (RuntimeException exception) {
            throw new IllegalArgumentException("member-support cryptographic keys are invalid", exception);
        }
    }
}
