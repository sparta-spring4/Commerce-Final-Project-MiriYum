package com.miriyum.domain.storeoperator.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 매장 운영자 회원가입 요청이다. {@code docs/specs/auth-account/openapi.yaml}의
 * {@code StoreOperatorSignUpRequest}와 대응한다.
 *
 * <p>승인된 스키마에는 전화번호 필드가 없다. 본인확인 제공업체가 아직 선정되지 않아, 서버가
 * {@code identityVerificationReference}를 해석해 실제 전화번호를 얻어오는 어댑터가 없으므로
 * 이번 구현은 형식(공백 아님)만 검증하고 계정의 전화번호는 채우지 않는다(BLOCKED).</p>
 */
public record StoreOperatorSignUpRequest(

        @NotBlank
        @Email
        @Size(max = 254)
        String email,

        @NotBlank
        @Size(min = 8, max = 64)
        String password,

        @NotBlank
        String passwordConfirm,

        @NotBlank
        @Size(min = 1, max = 512)
        String emailVerificationReference,

        @NotBlank
        @Size(min = 1, max = 512)
        String identityVerificationReference,

        @NotBlank
        @Size(min = 2, max = 50)
        String displayName
) {
}
