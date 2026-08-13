package com.miriyum.domain.auth.social.dto;

import com.miriyum.domain.auth.social.enums.KakaoOAuthPurpose;

import com.miriyum.domain.auth.jwt.TokenNamespace;

/** 카카오 콜백에서 검증하는 계정 유형·요청 목적·연결 대상의 서명된 상태값이다. */
public record KakaoOAuthState(
        TokenNamespace namespace,
        KakaoOAuthPurpose purpose,
        Long accountId
) {
}
