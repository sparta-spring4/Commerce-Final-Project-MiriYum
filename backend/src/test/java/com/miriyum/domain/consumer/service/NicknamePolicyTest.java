package com.miriyum.domain.consumer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.CommonErrorCode;
import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class NicknamePolicyTest {

    private final NicknamePolicy nicknamePolicy = new NicknamePolicy();

    @Test
    @DisplayName("허용된 형식의 닉네임은 그대로 통과한다")
    void acceptsValidNickname() {
        // given
        String nickname = "닉네임";

        // when
        String normalized = nicknamePolicy.normalize(nickname);

        // then
        assertThat(normalized).isEqualTo("닉네임");
    }

    @Test
    @DisplayName("앞뒤 공백을 제거하고 연속 공백을 하나로 정규화한다")
    void trimsAndCollapsesWhitespace() {
        // given
        String nickname = "  닉  네임  ";

        // when
        String normalized = nicknamePolicy.normalize(nickname);

        // then
        assertThat(normalized).isEqualTo("닉 네임");
    }

    @Test
    @DisplayName("정규화 후 2자 미만이면 거부한다")
    void rejectsTooShortNickname() {
        // given
        String nickname = " a ";

        // when & then
        assertThatThrownBy(() -> nicknamePolicy.normalize(nickname))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("20자를 초과하면 거부한다")
    void rejectsTooLongNickname() {
        // given
        String tooLong = "a".repeat(21);

        // when & then
        assertThatThrownBy(() -> nicknamePolicy.normalize(tooLong))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("허용되지 않은 문자가 있으면 거부한다")
    void rejectsDisallowedCharacters() {
        // given
        String nickname = "닉네임!";

        // when & then
        assertThatThrownBy(() -> nicknamePolicy.normalize(nickname))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("전화번호 형태의 닉네임은 거부한다")
    void rejectsPhoneNumberLikeNickname() {
        // given
        String nickname = "010-1234-5678";

        // when & then
        assertThatThrownBy(() -> nicknamePolicy.normalize(nickname))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }

    @Test
    @DisplayName("공식 계정으로 오인될 수 있는 예약어가 포함되면 거부한다")
    void rejectsReservedWord() {
        // given
        String nickname = "미리윰공식";

        // when & then
        assertThatThrownBy(() -> nicknamePolicy.normalize(nickname))
                .isInstanceOf(ServiceException.class)
                .extracting(exception -> ((ServiceException) exception).getErrorCode())
                .isEqualTo(CommonErrorCode.VALIDATION_FAILED);
    }
}
