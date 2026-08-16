package com.miriyum.domain.reservation.waiting.dto;

public record WaitingSettingDeactivationImpact(String storeId, long version, long activeTeamCount) {
    public WaitingSettingDeactivationImpact {
        if (storeId == null || !storeId.matches("[1-9][0-9]*")
                || version < 0 || activeTeamCount < 0) {
            throw new IllegalArgumentException("invalid waiting deactivation impact");
        }
    }
}
