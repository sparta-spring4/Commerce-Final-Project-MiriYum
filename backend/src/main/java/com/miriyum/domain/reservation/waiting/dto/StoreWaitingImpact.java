package com.miriyum.domain.reservation.waiting.dto;

import java.util.Set;

/** 한 Store의 활성 Waiting 팀 영향 projection이다. */
public record StoreWaitingImpact(long storeId, long activeTeamCount, Set<Long> waitingTeamIds) {
    public StoreWaitingImpact {
        if (storeId <= 0 || activeTeamCount < 0 || waitingTeamIds == null
                || activeTeamCount != waitingTeamIds.size()) {
            throw new IllegalArgumentException("waiting impact is invalid");
        }
        waitingTeamIds = Set.copyOf(waitingTeamIds);
    }
}
