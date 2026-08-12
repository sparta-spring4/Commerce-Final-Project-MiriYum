package com.miriyum.domain.consumer.dto.request;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/** 카카오 최초 로그인 뒤 일반 사용자 계정을 만들기 위해 받는 서비스 필수 정보다. */
public record ConsumerKakaoSignUpRequest(
        @NotBlank String signUpTicket,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank String phoneNumber,
        @AssertTrue boolean ageConfirmed,
        @NotBlank @Size(min = 2, max = 20) @Pattern(regexp = "^[가-힣A-Za-z0-9 _-]+$") String nickname
) {
}
