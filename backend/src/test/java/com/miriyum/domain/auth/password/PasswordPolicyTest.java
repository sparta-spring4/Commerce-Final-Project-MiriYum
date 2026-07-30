package com.miriyum.domain.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.text.Normalizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PasswordPolicyTest {

    private final PasswordPolicy passwordPolicy = new PasswordPolicy();

    @Test
    @DisplayName("영문 대소문자·숫자·특수문자 중 3종 이상을 포함하면 통과한다")
    void acceptsPasswordWithThreeCharacterClasses() {
        // given
        String password = "Password123";

        // when
        String normalized = passwordPolicy.normalize(password);

        // then
        assertThat(normalized).isEqualTo("Password123");
    }

    @Test
    @DisplayName("8자 미만이면 거부한다")
    void rejectsTooShortPassword() {
        // given
        String password = "Abc123!";

        // when & then
        assertThatThrownBy(() -> passwordPolicy.normalize(password))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("64자를 초과하면 거부한다")
    void rejectsTooLongPassword() {
        // given
        String password = "Aa1!".repeat(17);

        // when & then
        assertThatThrownBy(() -> passwordPolicy.normalize(password))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("영문 소문자와 숫자 2종만 포함하면 거부한다")
    void rejectsPasswordWithOnlyTwoCharacterClasses() {
        // given
        String password = "password123";

        // when & then
        assertThatThrownBy(() -> passwordPolicy.normalize(password))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("특수문자를 포함해 3종을 채우면 통과한다")
    void acceptsPasswordWithSpecialCharacterAsThirdClass() {
        // given
        String password = "password!23";

        // when & then
        assertThat(passwordPolicy.normalize(password)).isEqualTo("password!23");
    }

    @Test
    @DisplayName("저장 전 NFC로 정규화한다")
    void normalizesToNfcBeforeReturning() {
        // given: NFD로 분해된 결합 문자(자음+모음)
        String nfdPassword = Normalizer.normalize("password123가", Normalizer.Form.NFD);

        // when
        String normalized = passwordPolicy.normalize(nfdPassword);

        // then
        assertThat(normalized).isEqualTo(Normalizer.normalize(nfdPassword, Normalizer.Form.NFC));
    }
}
