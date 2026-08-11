package com.miriyum.domain.menu.dto.storeoperator;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 메뉴 게시 취소·종료 명령의 감사 사유다. */
public record MenuChangeReasonRequest(
        @NotBlank @Size(max = 500) String changeReason
) {
}
