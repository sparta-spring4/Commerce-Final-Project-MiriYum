package com.miriyum.domain.reservation.waiting.dto;

/**
 * 기능 비활성화 판단에 필요한 현재 설정 version과 활성 팀 수를 노출한다.
 *
 * @param storeId 정밀도 손실을 막기 위한 문자열 매장 ID
 * @param version 현재 설정 version, 설정 부재 시 0
 * @param activeTeamCount #272 공개 계약이 집계한 활성 팀 수
 */
public record WaitingSettingDeactivationImpact(String storeId, long version, long activeTeamCount) {
    public WaitingSettingDeactivationImpact {
        if (storeId == null || !storeId.matches("[1-9][0-9]*")
                || version < 0 || activeTeamCount < 0) {
            throw new IllegalArgumentException("invalid waiting deactivation impact");
        }
    }
}
