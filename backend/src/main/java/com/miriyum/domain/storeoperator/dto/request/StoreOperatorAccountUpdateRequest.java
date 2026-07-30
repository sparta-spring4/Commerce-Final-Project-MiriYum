package com.miriyum.domain.storeoperator.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 1차 MVP의 매장 운영자 본인 정보 수정은 표시 이름만 지원한다.
 */
public record StoreOperatorAccountUpdateRequest(

        @NotBlank
        @Size(min = 2, max = 50)
        String displayName
) {
}
