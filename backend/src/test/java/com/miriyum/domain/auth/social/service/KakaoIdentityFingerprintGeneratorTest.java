package com.miriyum.domain.auth.social.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.domain.auth.social.dto.KakaoIdentityFingerprint;
import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class KakaoIdentityFingerprintGeneratorTest {

    @Test
    @DisplayName("현재 키와 이전 키로 카카오 식별자 fingerprint를 각각 생성한다")
    void generatesFingerprintsForActiveAndPreviousKeys() {
        KakaoIdentityFingerprintGenerator generator = new KakaoIdentityFingerprintGenerator(
                "v2", "active-fingerprint-secret", "v1", "previous-fingerprint-secret");

        KakaoIdentityFingerprint active = generator.generateActive("kakao-subject");
        KakaoIdentityFingerprint previous = generator.generatePrevious("kakao-subject").orElseThrow();

        assertThat(active.keyVersion()).isEqualTo("v2");
        assertThat(previous.keyVersion()).isEqualTo("v1");
        assertThat(active.value()).hasSize(64).isNotEqualTo(previous.value());
    }

    @Test
    @DisplayName("현재 fingerprint 키가 없으면 카카오 로그인 처리를 실패 폐쇄한다")
    void rejectsMissingActiveFingerprintKey() {
        KakaoIdentityFingerprintGenerator generator = new KakaoIdentityFingerprintGenerator("", "", "", "");

        assertThatThrownBy(() -> generator.generateActive("kakao-subject"))
                .isInstanceOfSatisfying(ServiceException.class, exception ->
                        assertThat(exception.getErrorCode()).isEqualTo(CommonErrorCode.SERVICE_UNAVAILABLE));
    }
}
