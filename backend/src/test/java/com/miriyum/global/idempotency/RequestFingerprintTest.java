package com.miriyum.global.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class RequestFingerprintTest {

    @Test
    @DisplayName("같은 입력은 같은 해시를 낸다")
    void of_sameInput_sameHash() {
        String input = "POST|/api/v1/reservations|storeId=1|serviceDate=2026-08-01";
        assertThat(RequestFingerprint.of(input)).isEqualTo(RequestFingerprint.of(input));
    }

    @Test
    @DisplayName("리소스 ID가 다르면 다른 해시를 낸다")
    void of_differentInput_differentHash() {
        assertThat(RequestFingerprint.of("POST|/api/v1/stores/1|x"))
                .isNotEqualTo(RequestFingerprint.of("POST|/api/v1/stores/2|x"));
    }

    @Test
    @DisplayName("해시는 소문자 hex 64자다")
    void of_isLowercaseHex64() {
        assertThat(RequestFingerprint.of("anything")).matches("^[0-9a-f]{64}$");
    }
}
