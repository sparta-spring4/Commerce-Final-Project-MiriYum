package com.miriyum.domain.auth.dto.response;

/**
 * 가입 성공 응답에 담기는 계정 유형이다. {@code docs/specs/auth-account/openapi.yaml}의
 * {@code AccountCreatedData.accountType}과 대응한다.
 */
public enum AccountType {
    CONSUMER,
    STORE_OPERATOR
}
