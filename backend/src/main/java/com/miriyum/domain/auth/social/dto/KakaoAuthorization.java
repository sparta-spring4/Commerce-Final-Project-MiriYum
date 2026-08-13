package com.miriyum.domain.auth.social.dto;

/** 카카오 인가 주소와 발급한 브라우저 검증용 state를 함께 전달하는 내부 값이다. */
public record KakaoAuthorization(String authorizationUrl, String state) {
}
