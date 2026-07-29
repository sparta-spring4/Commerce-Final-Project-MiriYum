package com.miriyum.domain.auth.dto.response;

/**
 * 가입 성공 응답이다. {@code docs/specs/auth-account/openapi.yaml}의
 * {@code AccountCreatedData}(accountId, accountType, status)와 대응한다.
 */
public record AccountCreatedResponse(
        String accountId,
        AccountType accountType,
        String status
) {

    public static AccountCreatedResponse of(Long accountId, AccountType accountType) {
        return new AccountCreatedResponse(String.valueOf(accountId), accountType, "ACTIVE");
    }
}
