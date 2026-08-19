package com.miriyum.domain.reservation.waiting.dto;

import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeamStatus;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.MemberRole;
import com.miriyum.domain.reservation.waiting.dto.WaitingPartyContracts.MemberSnapshot;

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
        long version,
        List<MemberSnapshot> memberships
) {

    public WaitingConsumerSnapshot(
            String waitingTeamId, String storeId, LocalDate businessDate,
            WaitingTeamStatus status, long queueSequence, long teamsAhead, int partySize,
            Instant createdAt, Instant calledAt, Instant arrivalDeadline, Instant arrivedAt,
            Instant cancelledAt, long version
    ) {
        this(waitingTeamId, storeId, businessDate, status, queueSequence, teamsAhead,
                partySize, createdAt, calledAt, arrivalDeadline, arrivedAt, cancelledAt,
                version, List.of());
    }

    public WaitingConsumerSnapshot {
        memberships = memberships == null ? List.of() : List.copyOf(memberships);
    }

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
                team.getVersion(),
                List.of());
    }

    public static WaitingConsumerSnapshot from(
            WaitingTeam team,
            long teamsAhead,
            List<WaitingActiveMembership> memberships,
            long viewerAccountId
    ) {
        List<MemberSnapshot> memberSnapshots = memberships.stream()
                .map(membership -> new MemberSnapshot(
                        Long.toString(membership.getId()),
                        membership.getConsumerAccountId().equals(team.getConsumerAccountId())
                                ? MemberRole.REPRESENTATIVE : MemberRole.MEMBER,
                        membership.getCreatedAt(),
                        membership.getConsumerAccountId() == viewerAccountId))
                .toList();
        WaitingConsumerSnapshot base = from(team, teamsAhead);
        return new WaitingConsumerSnapshot(
                base.waitingTeamId(), base.storeId(), base.businessDate(), base.status(),
                base.queueSequence(), base.teamsAhead(), base.partySize(), base.createdAt(),
                base.calledAt(), base.arrivalDeadline(), base.arrivedAt(), base.cancelledAt(),
                base.version(), memberSnapshots);
    }
}
