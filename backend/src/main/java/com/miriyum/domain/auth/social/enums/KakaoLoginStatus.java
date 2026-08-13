package com.miriyum.domain.auth.social.enums;

/** 카카오 인증 뒤 즉시 로그인됐는지, 서비스 가입 정보가 더 필요한지 구분한다. */
public enum KakaoLoginStatus {
    AUTHENTICATED,
    SIGN_UP_REQUIRED
}
