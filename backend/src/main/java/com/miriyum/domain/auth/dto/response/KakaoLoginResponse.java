package com.miriyum.domain.auth.dto.response;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.miriyum.domain.auth.social.dto.KakaoLoginResult;
import com.miriyum.domain.auth.social.enums.KakaoLoginStatus;

/** 카카오 로그인 결과다. 가입이 필요하면 Access Token 대신 짧은 가입 티켓만 반환한다. */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record KakaoLoginResponse(
        KakaoLoginStatus status,
        String accessToken,
        String tokenType,
        Long expiresIn,
        String signUpTicket
) {

    public static KakaoLoginResponse from(KakaoLoginResult result, long accessTokenValiditySeconds) {
        if (result.status() == KakaoLoginStatus.SIGN_UP_REQUIRED) {
            return new KakaoLoginResponse(result.status(), null, null, null, result.signUpTicket());
        }
        return new KakaoLoginResponse(
                result.status(),
                result.tokenPair().accessToken(),
                "Bearer",
                accessTokenValiditySeconds,
                null);
    }
}
