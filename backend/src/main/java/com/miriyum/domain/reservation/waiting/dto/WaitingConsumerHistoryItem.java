package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import java.time.Instant;
import java.time.LocalDate;

/** 소비자 과거 웨이팅 목록에 필요한 종결 사실만 공개한다. */
public record WaitingConsumerHistoryItem(
        String waitingTeamId,
        String storeId,
        LocalDate businessDate,
        WaitingTeamStatus status,
        long queueSequence,
        int partySize,
        Instant createdAt,
        Instant endedAt,
        String reservationId
) {
    public static WaitingConsumerHistoryItem from(WaitingTeam team) {
        Instant endedAt = switch (team.getStatus()) {
            case CHECKED_IN -> team.getCheckedInAt();
            case CANCELLED -> team.getCancelledAt();
            case NO_SHOW -> team.getNoShowAt();
            case CLOSED_BY_STORE -> team.getClosedByStoreAt();
            case RESERVATION_CONVERTED -> team.getReservationConvertedAt();
            default -> null;
        };
        return new WaitingConsumerHistoryItem(
                Long.toString(team.getId()), Long.toString(team.getStoreId()),
                team.getBusinessDate(), team.getStatus(), team.getQueueSequence(),
                team.getPartySize(), team.getCreatedAt(), endedAt,
                team.getReservationReferenceId() == null
                        ? null : Long.toString(team.getReservationReferenceId()));
    }
}
