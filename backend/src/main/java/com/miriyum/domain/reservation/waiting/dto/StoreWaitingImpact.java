package com.miriyum.domain.reservation.waiting.dto;

/** 한 Store의 활성 Waiting 팀 영향 projection이다. */
public record StoreWaitingImpact(long storeId, long activeTeamCount) {
    public StoreWaitingImpact {
        if (storeId <= 0 || activeTeamCount < 0) {
            throw new IllegalArgumentException("waiting impact is invalid");
        }
    }
}
