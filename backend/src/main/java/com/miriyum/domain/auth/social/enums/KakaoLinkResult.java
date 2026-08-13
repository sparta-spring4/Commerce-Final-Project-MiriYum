package com.miriyum.domain.auth.social.enums;

/** 카카오 로그인 수단 연결의 생성·멱등 결과다. */
public enum KakaoLinkResult {
    CREATED,        // 성공
    ALREADY_LINKED  // 이미 연결됨
}
