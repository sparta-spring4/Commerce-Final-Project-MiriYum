package com.miriyum.domain.auth.password;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import java.nio.charset.StandardCharsets;
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
    @DisplayName("이모지가 포함된 비밀번호를 거부한다")
    void rejectsPasswordContainingEmoji() {
        // given
        String password = "Password123!\uD83D\uDE00";

        // when & then
        assertThatThrownBy(() -> passwordPolicy.normalize(password))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Unicode 기본 이모지 U+231A가 포함된 비밀번호를 거부한다")
    void rejectsPasswordContainingDefaultEmojiOutsideManualRanges() {
        // given: U+231A WATCH는 기존에 손으로 정한 범위 밖의 기본 이모지다.
        String password = "Abcdef1\u231A";

        // when & then
        assertThatThrownBy(() -> passwordPolicy.normalize(password))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("Unicode 기본 이모지와 조합 제어 문자가 포함된 비밀번호를 거부한다")
    void rejectsPasswordContainingDefaultEmojiAndJoiner() {
        // given: U+231A는 기존 수동 범위에 빠져 있던 기본 이모지이며, 뒤의 ZWJ는 조합 제어 문자다.
        String password = "Abcdef1\u231A\u200D";

        // when & then
        assertThatThrownBy(() -> passwordPolicy.normalize(password))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
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

    @Test
    @DisplayName("UTF-8로 72바이트를 넘어도 64 code point 이내면 통과한다")
    void acceptsMultiBytePasswordWithinCodePointLimitEvenBeyondBcryptByteLimit() {
        // given: 한글 25자(코드포인트 25개, UTF-8 3바이트씩 75바이트) + 영문 대소문자·숫자로 3종 충족
        String password = "가".repeat(25) + "Aa1";

        // when
        String normalized = passwordPolicy.normalize(password);

        // then: 28 code point로 AUTH-006의 8~64자 범위 안이므로 BCrypt의 72바이트 한도와 무관하게
        // 통과해야 한다(byte 한도는 Sha256BCryptPasswordEncoder의 전처리가 흡수한다).
        assertThat(password.getBytes(StandardCharsets.UTF_8).length).isGreaterThan(72);
        assertThat(normalized).isEqualTo(password);
    }

    @Test
    @DisplayName("64 code point를 넘으면 거부한다")
    void rejectsPasswordOverSixtyFourCodePoints() {
        // given: 한글 65자(코드포인트 65개) + 3종 충족
        String password = "가".repeat(65) + "Aa1";

        // when & then
        assertThatThrownBy(() -> passwordPolicy.normalize(password))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("toNfc는 검증 없이 NFC 정규화만 한다")
    void toNfcNormalizesWithoutValidating() {
        // given: 형식 검증을 통과 못 할 짧은 NFD 문자열(로그인 시 잘못된 비밀번호로 들어올 수 있는 입력)
        String shortNfd = Normalizer.normalize("가", Normalizer.Form.NFD);

        // when
        String result = passwordPolicy.toNfc(shortNfd);

        // then: 예외 없이 NFC로만 변환된다
        assertThat(result).isEqualTo(Normalizer.normalize(shortNfd, Normalizer.Form.NFC));
    }
}
