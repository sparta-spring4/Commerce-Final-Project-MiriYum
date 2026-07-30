package com.miriyum.domain.auth.ratelimit;

/**
 * 요청 성격별 속도 제한 등급이다. 각 등급의 한도값은 {@code application.yml}에서 관리하며
 * {@code docs/service-policies/18-scale-reliability.md} SCALE-005의 2026-07-30 팀 결정을 따른다.
 */
public enum RateLimitCategory {
    SIGN_UP,
    LOGIN,
    TOKEN_REFRESH,
    CSRF_PREPARATION
}
