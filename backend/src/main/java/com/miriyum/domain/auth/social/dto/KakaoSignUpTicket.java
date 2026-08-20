package com.miriyum.domain.auth.social.dto;

import com.miriyum.domain.auth.jwt.TokenNamespace;

/** 카카오 첫 로그인 뒤 서비스 필수 정보를 받기 위해 발급하는 짧은 수명의 가입 티켓이다. */
public record KakaoSignUpTicket(
        TokenNamespace namespace,
        String providerSubjectFingerprint,
        String fingerprintKeyVersion
) {
}
