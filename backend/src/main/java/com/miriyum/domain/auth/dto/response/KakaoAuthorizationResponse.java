package com.miriyum.domain.auth.dto.response;

/** 프론트엔드가 사용자를 카카오 인증 화면으로 이동시키기 위한 인가 주소다. */
public record KakaoAuthorizationResponse(String authorizationUrl) {
}
