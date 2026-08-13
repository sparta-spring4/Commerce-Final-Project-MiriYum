package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import java.time.Instant;

/** 소비자 식별자를 제외한 운영자용 웨이팅 팀 원장 snapshot이다. */
public record WaitingTeamSnapshot(
        String waitingTeamId,
        String storeId,
        WaitingTeamStatus status,
        long queueSequence,
        int partySize,
        Instant createdAt,
        Instant calledAt,
        Instant arrivedAt,
        Instant checkedInAt,
        Instant cancelledAt,
        long version
) {

    /** 현재 aggregate를 개인정보 안전한 운영자 응답으로 투영한다. */
    public static WaitingTeamSnapshot from(WaitingTeam team) {
        return new WaitingTeamSnapshot(
                Long.toString(team.getId()),
                Long.toString(team.getStoreId()),
                team.getStatus(),
                team.getQueueSequence(),
                team.getPartySize(),
                team.getCreatedAt(),
                team.getCalledAt(),
                team.getArrivedAt(),
                team.getCheckedInAt(),
                team.getCancelledAt(),
                team.getVersion()
        );
    }
}
