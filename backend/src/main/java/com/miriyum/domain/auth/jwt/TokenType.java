package com.miriyum.domain.auth.jwt;

/**
 * Access/Refresh 용도가 서로 교차 사용되지 않도록 토큰 내부에 표시하는 구분값이다.
 */
public enum TokenType {
    ACCESS,
    REFRESH
}
