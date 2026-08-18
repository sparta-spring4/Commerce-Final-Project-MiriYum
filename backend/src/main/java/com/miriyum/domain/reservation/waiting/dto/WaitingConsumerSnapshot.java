package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import java.time.Instant;
import java.time.LocalDate;

/** 소비자 본인에게 공개할 수 있는 현재 웨이팅 snapshot이다. */
public record WaitingConsumerSnapshot(
        String waitingTeamId,
        String storeId,
        LocalDate businessDate,
        WaitingTeamStatus status,
        long queueSequence,
        long teamsAhead,
        int partySize,
        Instant createdAt,
        Instant calledAt,
        Instant arrivalDeadline,
        Instant arrivedAt,
        Instant cancelledAt,
        long version
) {

    public static WaitingConsumerSnapshot from(WaitingTeam team, long teamsAhead) {
        if (teamsAhead < 0) {
            throw new IllegalArgumentException("teamsAhead must not be negative");
        }
        return new WaitingConsumerSnapshot(
                Long.toString(team.getId()),
                Long.toString(team.getStoreId()),
                team.getBusinessDate(),
                team.getStatus(),
                team.getQueueSequence(),
                teamsAhead,
                team.getPartySize(),
                team.getCreatedAt(),
                team.getCalledAt(),
                team.getArrivalDeadline(),
                team.getArrivedAt(),
                team.getCancelledAt(),
                team.getVersion());
    }
}
