package com.miriyum.domain.auth.social.dto;

import jakarta.validation.constraints.NotBlank;

/** 프론트엔드가 카카오 콜백에서 전달받은 인가 코드·state·redirect URI다. */
public record KakaoAuthenticationRequest(
        @NotBlank String authorizationCode,
        @NotBlank String state,
        @NotBlank String redirectUri
) {
}
