package com.miriyum.domain.consumer.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 1차 MVP의 일반 사용자 본인 정보 수정은 닉네임만 지원한다.
 */
public record ConsumerAccountUpdateRequest(

        @NotBlank
        @Size(min = 2, max = 20)
        @Pattern(regexp = "^[가-힣A-Za-z0-9 _-]+$")
        String nickname
) {
}
