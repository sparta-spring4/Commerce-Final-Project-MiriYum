package com.miriyum.domain.auth.social.dto;

import com.miriyum.domain.auth.social.enums.KakaoLoginStatus;

import com.miriyum.domain.auth.jwt.TokenPair;

/** 카카오 인증 결과다. 첫 가입이면 토큰 대신 짧은 가입 티켓만 반환한다. */
public record KakaoLoginResult(
        KakaoLoginStatus status,
        TokenPair tokenPair,
        String signUpTicket
) {

    public static KakaoLoginResult signUpRequired(String signUpTicket) {
        return new KakaoLoginResult(KakaoLoginStatus.SIGN_UP_REQUIRED, null, signUpTicket);
    }

    public static KakaoLoginResult authenticated(TokenPair tokenPair) {
        return new KakaoLoginResult(KakaoLoginStatus.AUTHENTICATED, tokenPair, null);
    }
}
