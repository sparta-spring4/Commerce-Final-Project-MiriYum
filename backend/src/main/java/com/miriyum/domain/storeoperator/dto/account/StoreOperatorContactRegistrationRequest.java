package com.miriyum.domain.storeoperator.dto.account;

import jakarta.validation.constraints.NotBlank;

/**
 * 기존 매장 운영자의 최초 연락처 등록 요청이다.
 */
public record StoreOperatorContactRegistrationRequest(
        @NotBlank String phoneNumber
) {
}
