package com.miriyum.domain.reservation.waiting.dto;

/** 웨이팅 설정 변경 전에 확인하는 매장별 활성 팀 영향이다. */
public record WaitingActiveTeamImpact(long storeId, long activeTeamCount) {

    public WaitingActiveTeamImpact {
        if (storeId <= 0 || activeTeamCount < 0) {
            throw new IllegalArgumentException("storeId must be positive and activeTeamCount non-negative");
        }
    }
}
