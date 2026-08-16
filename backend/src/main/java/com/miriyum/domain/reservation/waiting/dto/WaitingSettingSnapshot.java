package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingReceptionMode;
import com.miriyum.domain.reservation.waiting.entity.WaitingSetting;

/**
 * 매장의 현재 웨이팅 운영 설정을 공개 API 계약으로 투영한다.
 *
 * @param storeId 정밀도 손실을 막기 위한 문자열 매장 ID
 * @param enabled 신규 웨이팅 접수 기능 사용 여부
 * @param receptionMode 접수 방식
 * @param advanceOpenMinutes AUTO 사전 오픈 분
 * @param version 설정 부재 시 0, 저장된 설정은 1 이상
 */
public record WaitingSettingSnapshot(
        String storeId,
        boolean enabled,
        WaitingReceptionMode receptionMode,
        int advanceOpenMinutes,
        long version
) {
    /**
     * 저장된 설정이 없는 매장의 실패 폐쇄 기본값을 만든다.
     *
     * @param storeId 매장 ID
     * @return {@code false / PAUSED / 60 / version 0} snapshot
     */
    public static WaitingSettingSnapshot defaults(long storeId) {
        return new WaitingSettingSnapshot(
                Long.toString(storeId), false, WaitingReceptionMode.PAUSED, 60, 0L);
    }

    /**
     * 영속 설정을 공개 snapshot으로 변환한다.
     *
     * @param setting 현재 설정
     * @return 공개 설정 snapshot
     */
    public static WaitingSettingSnapshot from(WaitingSetting setting) {
        return new WaitingSettingSnapshot(Long.toString(setting.getStoreId()), setting.isEnabled(),
                setting.getReceptionMode(), setting.getAdvanceOpenMinutes(), setting.getVersion());
    }
}
