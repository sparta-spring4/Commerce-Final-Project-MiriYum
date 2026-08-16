package com.miriyum.domain.reservation.waiting.dto;

public record WaitingSettingDeactivationImpact(long storeId, long version, long activeTeamCount) {
    public WaitingSettingDeactivationImpact {
        if (storeId <= 0 || version < 0 || activeTeamCount < 0) {
            throw new IllegalArgumentException("invalid waiting deactivation impact");
        }
    }
}
