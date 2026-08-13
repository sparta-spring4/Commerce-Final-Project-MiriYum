package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import java.time.Instant;

/** 운영자 FIFO 목록에 필요한 개인정보 안전 필드만 제공한다. */
public record WaitingTeamListItem(
        String waitingTeamId,
        WaitingTeamStatus status,
        long queueSequence,
        int partySize,
        Instant createdAt,
        long version
) {

    /** 원장 aggregate를 목록용 공개 projection으로 변환한다. */
    public static WaitingTeamListItem from(WaitingTeam team) {
        return new WaitingTeamListItem(
                Long.toString(team.getId()),
                team.getStatus(),
                team.getQueueSequence(),
                team.getPartySize(),
                team.getCreatedAt(),
                team.getVersion());
    }
}
