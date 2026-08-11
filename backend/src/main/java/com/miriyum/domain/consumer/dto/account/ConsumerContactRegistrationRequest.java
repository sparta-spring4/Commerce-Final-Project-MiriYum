package com.miriyum.domain.consumer.dto.account;

import jakarta.validation.constraints.NotBlank;

/**
 * 기존 일반 사용자의 최초 연락처 등록 요청이다.
 */
public record ConsumerContactRegistrationRequest(
        @NotBlank String phoneNumber
) {
}
