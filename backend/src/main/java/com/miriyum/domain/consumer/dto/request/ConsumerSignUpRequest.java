package com.miriyum.domain.consumer.dto.request;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 일반 사용자 회원가입 요청이다. {@code docs/specs/auth-account/openapi.yaml}의
 * {@code ConsumerSignUpRequest}와 대응한다.
 *
 * <p>{@code phone}은 이메일·본인확인 제공업체가 아직 선정되지 않아 승인된 OpenAPI 스키마에는
 * 없는 개발용 임시 필드다. 실제 제공업체 연동이 확정되면 본인확인 참조에서 서버가 직접 해석하도록
 * 교체하고 이 필드는 제거해야 한다.</p>
 */
public record ConsumerSignUpRequest(

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
        @Size(min = 2, max = 20)
        String name,

        @NotBlank
        String phone
) {
}
