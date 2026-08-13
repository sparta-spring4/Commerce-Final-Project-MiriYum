package com.miriyum.domain.auth.dto.request;

import jakarta.validation.constraints.NotBlank;

/** 카카오 인가 주소를 만들 때 허용 목록 검증에 쓰는 콜백 주소 요청이다. */
public record KakaoAuthorizationRequest(@NotBlank String redirectUri) {
}
