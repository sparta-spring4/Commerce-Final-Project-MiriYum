package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;

public record WaitingSettingSnapshot(
        String storeId,
        boolean enabled,
        WaitingReceptionMode receptionMode,
        int advanceOpenMinutes,
        long version
) {
    public static WaitingSettingSnapshot defaults(long storeId) {
        return new WaitingSettingSnapshot(
                Long.toString(storeId), false, WaitingReceptionMode.PAUSED, 60, 0L);
    }

    public static WaitingSettingSnapshot from(WaitingSetting setting) {
        return new WaitingSettingSnapshot(Long.toString(setting.getStoreId()), setting.isEnabled(),
                setting.getReceptionMode(), setting.getAdvanceOpenMinutes(), setting.getVersion());
    }
}
