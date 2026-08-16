package com.miriyum.domain.platformoperator.membersupport;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.platformoperator.service.membersupport.MemberSupportCrypto;
import java.util.Arrays;
import java.util.Base64;
import org.junit.jupiter.api.Test;

class MemberSupportCryptoTest {

    private static final String OLD_KEY = key((byte) 1);
    private static final String NEW_KEY = key((byte) 2);

    @Test
    void prefixesCiphertextWithActiveKeyVersion() {
        MemberSupportCrypto crypto = new MemberSupportCrypto("proof", 8, NEW_KEY, 0, "");

        byte[] encrypted = crypto.encrypt("private@example.com");

        assertThat(Byte.toUnsignedInt(encrypted[0])).isEqualTo(8);
        assertThat(crypto.decrypt(encrypted)).isEqualTo("private@example.com");
    }

    @Test
    void rotatedCryptoReadsPreviousVersionAndWritesOnlyActiveVersion() {
        MemberSupportCrypto oldCrypto = new MemberSupportCrypto("proof", 7, OLD_KEY, 0, "");
        byte[] oldPayload = oldCrypto.encrypt("private@example.com");
        MemberSupportCrypto rotated = new MemberSupportCrypto("proof", 8, NEW_KEY, 7, OLD_KEY);

        assertThat(rotated.decrypt(oldPayload)).isEqualTo("private@example.com");
        assertThat(Byte.toUnsignedInt(rotated.encrypt("next@example.com")[0])).isEqualTo(8);
    }

    @Test
    void rejectsUnknownOrTamperedKeyVersion() {
        MemberSupportCrypto crypto = new MemberSupportCrypto("proof", 8, NEW_KEY, 0, "");
        byte[] encrypted = crypto.encrypt("private@example.com");
        byte[] unknownVersion = Arrays.copyOf(encrypted, encrypted.length);
        unknownVersion[0] = 7;

        assertThatThrownBy(() -> crypto.decrypt(unknownVersion))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("invalid encrypted payload");
    }

    private static String key(byte value) {
        byte[] bytes = new byte[32];
        Arrays.fill(bytes, value);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
