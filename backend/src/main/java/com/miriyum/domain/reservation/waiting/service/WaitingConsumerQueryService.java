package com.miriyum.domain.reservation.waiting.service;

import com.miriyum.domain.consumer.service.ConsumerAccountService;
import com.miriyum.domain.reservation.exception.ReservationErrorCode;
import com.miriyum.domain.reservation.waiting.dto.WaitingConsumerSnapshot;
import com.miriyum.domain.reservation.waiting.dto.WaitingReceptionAvailability;
import com.miriyum.domain.reservation.waiting.entity.WaitingActiveMembership;
import com.miriyum.domain.reservation.waiting.entity.WaitingTeam;
import com.miriyum.domain.reservation.waiting.repository.WaitingActiveMembershipRepository;
import com.miriyum.domain.reservation.waiting.repository.WaitingTeamRepository;
import com.miriyum.global.exception.ServiceException;
import java.time.Clock;
import java.util.Objects;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 소비자 계정 범위에서 현재 활성 웨이팅만 조회하고 공개 snapshot으로 투영한다. */
@Service
public class WaitingConsumerQueryService {

    private final ConsumerAccountService accountService;
    private final WaitingActiveMembershipRepository membershipRepository;
    private final WaitingTeamRepository teamRepository;
    private final WaitingReceptionGate receptionGate;
    private final Clock clock;

    public WaitingConsumerQueryService(
            ConsumerAccountService accountService,
            WaitingActiveMembershipRepository membershipRepository,
            WaitingTeamRepository teamRepository,
            WaitingReceptionGate receptionGate,
            Clock clock
    ) {
        this.accountService = Objects.requireNonNull(accountService);
        this.membershipRepository = Objects.requireNonNull(membershipRepository);
        this.teamRepository = Objects.requireNonNull(teamRepository);
        this.receptionGate = Objects.requireNonNull(receptionGate);
        this.clock = Objects.requireNonNull(clock);
    }

    @Transactional(readOnly = true)
    public WaitingReceptionAvailability getAvailability(
            long consumerAccountId,
            long storeId
    ) {
        accountService.requireActiveAccount(consumerAccountId);
        return receptionGate.inspect(storeId, clock.instant());
    }

    @Transactional(readOnly = true)
    public WaitingConsumerSnapshot getCurrent(long consumerAccountId) {
        accountService.requireActiveAccount(consumerAccountId);
        WaitingActiveMembership membership = membershipRepository
                .findByConsumerAccountId(consumerAccountId)
                .orElseThrow(WaitingConsumerQueryService::notFound);
        WaitingTeam team = teamRepository.findById(membership.getWaitingTeamId())
                .filter(candidate -> candidate.getConsumerAccountId() == consumerAccountId)
                .orElseThrow(WaitingConsumerQueryService::notFound);
        return snapshot(team);
    }

    @Transactional(readOnly = true)
    public WaitingConsumerSnapshot getOwned(long consumerAccountId, long waitingTeamId) {
        accountService.requireActiveAccount(consumerAccountId);
        WaitingTeam team = teamRepository.findById(waitingTeamId)
                .filter(candidate -> candidate.getConsumerAccountId() == consumerAccountId)
                .orElseThrow(WaitingConsumerQueryService::notFound);
        return snapshot(team);
    }

    private WaitingConsumerSnapshot snapshot(WaitingTeam team) {
        long teamsAhead = teamRepository.countActiveAhead(
                team.getStoreId(), team.getBusinessDate(), team.getQueueSequence());
        return WaitingConsumerSnapshot.from(team, teamsAhead);
    }

    private static ServiceException notFound() {
        return new ServiceException(ReservationErrorCode.WAITING_TEAM_NOT_FOUND);
    }
}
