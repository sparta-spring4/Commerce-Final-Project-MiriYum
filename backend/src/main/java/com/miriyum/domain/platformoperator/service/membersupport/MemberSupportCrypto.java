package com.miriyum.domain.platformoperator.service.membersupport;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

@Component
public final class MemberSupportCrypto {
    private static final int NONCE_BYTES = 12;
    private static final int GCM_TAG_BITS = 128;
    private final byte[] digestKey;
    private final byte[] encryptionKey;
    private final SecureRandom random = new SecureRandom();

    @Autowired
    public MemberSupportCrypto(MemberSupportProperties properties) {
        this(properties.proofDigestSecret(), properties.piiEncryptionKey());
    }

    public MemberSupportCrypto(String proofDigestSecret, String base64EncryptionKey) {
        this.digestKey = proofDigestSecret.getBytes(StandardCharsets.UTF_8);
        this.encryptionKey = Base64.getDecoder().decode(base64EncryptionKey);
        if (digestKey.length == 0 || encryptionKey.length != 32) {
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
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            byte[] ciphertext = cipher.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            return ByteBuffer.allocate(nonce.length + ciphertext.length).put(nonce).put(ciphertext).array();
        } catch (GeneralSecurityException exception) {
            throw new IllegalStateException("AES-GCM unavailable", exception);
        }
    }

    public String decrypt(byte[] payload) {
        if (payload == null || payload.length <= NONCE_BYTES) {
            throw new IllegalArgumentException("invalid encrypted payload");
        }
        byte[] nonce = java.util.Arrays.copyOfRange(payload, 0, NONCE_BYTES);
        byte[] ciphertext = java.util.Arrays.copyOfRange(payload, NONCE_BYTES, payload.length);
        try {
            Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(encryptionKey, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, nonce));
            return new String(cipher.doFinal(ciphertext), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException exception) {
            throw new IllegalArgumentException("invalid encrypted payload", exception);
        }
    }
}
