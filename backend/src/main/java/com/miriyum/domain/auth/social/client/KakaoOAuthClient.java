package com.miriyum.domain.auth.social.client;

import com.miriyum.domain.auth.social.dto.KakaoOAuthUser;

/** 카카오 인가 코드를 서버에서 교환하고 사용자 식별자를 조회하는 외부 경계다. */
public interface KakaoOAuthClient {

    String createAuthorizationUrl(String state, String redirectUri);

    KakaoOAuthUser authenticate(String authorizationCode, String redirectUri);
}
