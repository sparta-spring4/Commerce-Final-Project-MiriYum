package com.miriyum.domain.storeoperator.dto.auth;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 카카오 최초 로그인 뒤 매장 운영자 계정을 만들기 위해 받는 서비스 필수 정보다. */
public record StoreOperatorKakaoSignUpRequest(
        @NotBlank String signUpTicket,
        @NotBlank @Email @Size(max = 254) String email,
        @NotBlank String phoneNumber,
        @NotBlank @Size(min = 2, max = 50) String displayName
) {
}
