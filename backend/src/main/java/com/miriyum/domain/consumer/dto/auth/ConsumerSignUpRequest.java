package com.miriyum.domain.consumer.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Size;

/**
 * 일반 사용자 회원가입 요청이다. {@code docs/specs/auth-account/openapi.yaml}의
 * {@code ConsumerSignUpRequest}와 대응한다.
 *
 * <p>1차 MVP에서는 실제 소유 인증 없이 사용자가 입력한 휴대전화를 신뢰 연락처로 저장한다.</p>
 */
public record ConsumerSignUpRequest(

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        // 최대 128: @Size는 UTF-16 code unit 기준이라 supplementary 문자가 섞이면 64
        // code point가 128 code unit까지 늘어날 수 있다. 정확한 64 code point 상한은
        // PasswordPolicy가 codePointCount로 검증한다.
        @NotBlank
        @Size(min = 8, max = 128)
        String password,

        @NotBlank
        String passwordConfirm,

        @NotBlank
        String phoneNumber,

        @AssertTrue
        boolean ageConfirmed,

        @NotBlank
        @Size(min = 2, max = 20)
        @Pattern(regexp = "^[가-힣A-Za-z0-9 _-]+$")
        String nickname
) {
}
