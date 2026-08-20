package com.miriyum.domain.auth.social.dto;

/** 카카오 토큰으로 조회한 로그인 판단용 최소 사용자 정보다. */
public record KakaoOAuthUser(String providerSubject) {
}
