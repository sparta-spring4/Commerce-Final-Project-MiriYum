package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingDisableAction;
import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/**
 * 현재 version을 기준으로 매장 웨이팅 설정 전체를 교체하는 요청이다.
 *
 * @param expectedVersion 요청자가 확인한 현재 version
 * @param enabled 신규 웨이팅 접수 기능 사용 여부
 * @param receptionMode 접수 방식
 * @param advanceOpenMinutes AUTO 사전 오픈 분
 * @param disableAction 기능 비활성화 시 활성 팀 처리 방식
 */
public record WaitingSettingUpdateRequest(
        @NotNull @PositiveOrZero Long expectedVersion,
        @NotNull Boolean enabled,
        @NotNull WaitingReceptionMode receptionMode,
        @NotNull @Min(0) @Max(180) Integer advanceOpenMinutes,
        WaitingDisableAction disableAction
) {
    @AssertTrue(message = "비활성화 설정은 PAUSED 모드여야 합니다.")
    public boolean isDisabledModeValid() {
        return enabled == null || enabled || receptionMode == null
                || receptionMode == WaitingReceptionMode.PAUSED;
    }

    @AssertTrue(message = "disableAction은 비활성화 요청에서만 허용됩니다.")
    public boolean isDisableActionValid() {
        return disableAction == null || Boolean.FALSE.equals(enabled);
    }
}
