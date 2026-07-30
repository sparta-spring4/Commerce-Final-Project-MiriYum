package com.miriyum.domain.auth.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;

/**
 * 계정 유형별 로그인 요청이다. 일반 사용자·매장 운영자 세션 생성 API가 공통으로 사용한다.
 */
public record LoginRequest(

        @NotBlank
        @Email
        String email,

        @NotBlank
        String password
) {
}
