package com.miriyum.domain.auth.contact;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.miriyum.global.exception.ServiceException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class PhoneNumberPolicyTest {

    private final PhoneNumberPolicy phoneNumberPolicy = new PhoneNumberPolicy();

    @Test
    @DisplayName("공백과 하이픈이 포함된 휴대전화 번호를 010 시작 11자리로 정규화한다")
    void normalizesPhoneNumber() {
        assertThat(phoneNumberPolicy.normalize(" 010-1234-5678 "))
                .isEqualTo("01012345678");
    }

    @Test
    @DisplayName("010 시작 11자리가 아닌 번호는 COMMON_001로 거절한다")
    void rejectsNonMvpPhoneNumber() {
        assertThatThrownBy(() -> phoneNumberPolicy.normalize("02-1234-5678"))
                .isInstanceOfSatisfying(ServiceException.class,
                        exception -> assertThat(exception.getErrorCode().getCode()).isEqualTo("COMMON_001"));
    }
}
